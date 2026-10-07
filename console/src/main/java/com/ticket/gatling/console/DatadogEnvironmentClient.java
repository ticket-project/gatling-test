package com.ticket.gatling.console;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

interface RunEnvironmentClient {
    List<DatadogRuntimeSnapshot> capture(DatadogTargetInput target) throws IOException, InterruptedException;
}

final class DatadogEnvironmentClient implements RunEnvironmentClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int QUERY_WINDOW_SECONDS = 1_800;
    private static final int MAX_ATTEMPTS = 3;
    private static final long ACTIVE_HOST_FRESHNESS_MILLIS = Duration.ofSeconds(90).toMillis();
    private static final long ABSOLUTE_HOST_FRESHNESS_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final Pattern POINT_PATTERN = Pattern.compile(
            "\\[\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s*,"
                    + "\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|null)\\s*]"
    );

    private final HttpClient httpClient;
    private final URI baseUri;
    private final String apiKey;
    private final String appKey;

    DatadogEnvironmentClient() {
        this(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
                siteUri(System.getenv("DATADOG_SITE")),
                System.getenv("DATADOG_API_KEY"),
                System.getenv("DATADOG_APP_KEY")
        );
    }

    DatadogEnvironmentClient(
            final HttpClient httpClient,
            final URI baseUri,
            final String apiKey,
            final String appKey
    ) {
        this.httpClient = Objects.requireNonNull(httpClient);
        this.baseUri = Objects.requireNonNull(baseUri);
        this.apiKey = trimToNull(apiKey);
        this.appKey = trimToNull(appKey);
    }

    @Override
    public List<DatadogRuntimeSnapshot> capture(final DatadogTargetInput target)
            throws IOException, InterruptedException {
        requireCredentials();
        final List<String> warnings = new ArrayList<>();
        final List<List<MetricSeries>> observed = new ArrayList<>();
        final String prefix = target.datadogMetricPrefix();
        final String app = applicationSelector(target);
        final List<MetricSeries> identity = requiredQuery("max:" + prefix + ".jvm_info{" + app
                + "} by {host,version,git.commit.sha,image_name,java_version,admission_enforcement}");
        observed.add(identity);
        final List<String> hosts = resolveActiveHosts(identity, Instant.now().toEpochMilli());

        final String infra = infrastructureSelector(target, hosts);
        final String container = infra + " AND container_name:" + target.datadogContainerName();
        final String containerTags = "host,container_id,container_name,image_id,image_name,image_tag";
        final List<MetricSeries> machineCpu = metric("system.cpu.num_cores", infra, "host", warnings, observed);
        final List<MetricSeries> machineRam = metric("system.mem.total", infra, "host", warnings, observed);
        final List<MetricSeries> containerCpu = metric("container.cpu.limit", container, containerTags, warnings, observed);
        final List<MetricSeries> containerMemory = metric("container.memory.limit", container, containerTags, warnings, observed);
        final List<MetricSeries> jvmGcMax = metric(prefix + ".jvm_gc_max_data_size_bytes", app, "host", warnings, observed);
        final List<MetricSeries> jvmMax = metric(prefix + ".jvm_memory_max_bytes", app, "host,area,id", warnings, observed);
        final List<MetricSeries> tomcatThreads = metric(prefix + ".tomcat_threads_config_max_threads", app, "host", warnings, observed);
        final List<MetricSeries> tomcatConnections = metric(prefix + ".tomcat_connections_config_max_connections", app, "host", warnings, observed);
        final List<MetricSeries> hikariMaximumPool = target.core()
                ? metric(prefix + ".hikaricp_connections_max", app, "host", warnings, observed)
                : List.of();
        final List<MetricSeries> redisMaxMemory = metric("redis.mem.maxmemory", infra, "host,redis_host", warnings, observed);

        final List<DatadogRuntimeSnapshot> snapshots = new ArrayList<>();
        for (String host : hosts) {
            final MetricSeries identitySeries = identity.stream()
                    .filter(series -> host.equals(series.tags().get("host")))
                    .max(java.util.Comparator.comparingLong(MetricSeries::timestamp))
                    .orElse(null);
            final MetricSeries containerSeries = latestSeries(containerCpu, host);
            snapshots.add(new DatadogRuntimeSnapshot(
                    latestObservedAt(observed, host),
                    identitySeries == null ? null : Instant.ofEpochMilli(identitySeries.timestamp()),
                    host,
                    tag(containerSeries, "container_id"),
                    toInteger(latestValue(machineCpu, host)),
                    toLong(latestValue(machineRam, host)),
                    nanocoresToCpu(latestValue(containerCpu, host)),
                    toLong(latestValue(containerMemory, host)),
                    firstPositiveLong(latestValue(jvmGcMax, host), maximumPositiveHeapValue(jvmMax, host)),
                    javaVersion(identitySeries),
                    commit(identitySeries),
                    firstNonBlank(tag(identitySeries, "image_name"), tag(containerSeries, "image_name")),
                    tag(containerSeries, "image_id"),
                    toInteger(latestValue(tomcatThreads, host)),
                    toInteger(latestValue(tomcatConnections, host)),
                    toInteger(latestValue(hikariMaximumPool, host)),
                    toLong(latestValue(redisMaxMemory, host)),
                    inferRedisNetworkLocation(redisMaxMemory, host),
                    booleanTag(identitySeries, "admission_enforcement"),
                    warnings
            ));
        }
        return List.copyOf(snapshots);
    }
    static List<MetricSeries> parseSeries(final String json) throws IOException {
        if (json == null || json.isBlank()) {
            throw new IOException("Datadog returned an empty response");
        }
        final int seriesKey = json.indexOf("\"series\"");
        if (seriesKey < 0) {
            throw new IOException("Datadog response does not contain a series array");
        }
        final int arrayStart = json.indexOf('[', seriesKey);
        if (arrayStart < 0) {
            throw new IOException("Datadog response has an invalid series array");
        }
        final int arrayEnd = matchingDelimiter(json, arrayStart, '[', ']');
        final List<MetricSeries> series = new ArrayList<>();
        int cursor = arrayStart + 1;
        while (cursor < arrayEnd) {
            final int objectStart = json.indexOf('{', cursor);
            if (objectStart < 0 || objectStart >= arrayEnd) {
                break;
            }
            final int objectEnd = matchingDelimiter(json, objectStart, '{', '}');
            final String object = json.substring(objectStart, objectEnd + 1);
            final String scope = stringValue(object, "scope");
            final MetricPoint latest = latestPoint(object);
            if (scope != null && latest != null) {
                series.add(new MetricSeries(scope, parseScope(scope), latest.timestamp(), latest.value()));
            }
            cursor = objectEnd + 1;
        }
        return List.copyOf(series);
    }

    private List<MetricSeries> metric(
            final String name,
            final String selector,
            final String byTags,
            final List<String> warnings,
            final List<List<MetricSeries>> observed
    ) throws InterruptedException {
        try {
            final List<MetricSeries> result = query("max:" + name + "{" + selector + "} by {" + byTags + "}");
            observed.add(result);
            return result;
        } catch (IOException exception) {
            warnings.add("query failed: " + name + " (" + safeError(exception) + ")");
            return List.of();
        }
    }

    private List<MetricSeries> requiredQuery(final String query) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            final List<MetricSeries> result = query(query);
            if (!result.isEmpty()) {
                return result;
            }
            if (attempt < MAX_ATTEMPTS) {
                Thread.sleep(500L * attempt);
            }
        }
        throw new IOException("Datadog did not return an active series for the automatically selected service");
    }
    private List<MetricSeries> query(final String query) throws IOException, InterruptedException {
        final long now = Instant.now().getEpochSecond();
        final String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        final URI uri = baseUri.resolve("/api/v1/query?from=" + (now - QUERY_WINDOW_SECONDS)
                + "&to=" + now + "&query=" + encoded);
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("DD-API-KEY", apiKey)
                .header("DD-APPLICATION-KEY", appKey)
                .GET()
                .build();
        for (int attempt = 1; ; attempt++) {
            IOException failure;
            boolean retry;
            try {
                final HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parseSeries(response.body());
                }
                failure = new IOException("Datadog query returned HTTP " + response.statusCode());
                retry = retryable(response.statusCode());
            } catch (IOException exception) {
                failure = exception;
                retry = true;
            }
            if (!retry || attempt == MAX_ATTEMPTS) {
                throw failure;
            }
            Thread.sleep(250L * attempt);
        }
    }

    private static String applicationSelector(final DatadogTargetInput target) {
        return "env:" + target.datadogEnv() + ",service:" + target.datadogService();
    }

    static List<String> resolveActiveHosts(
            final List<MetricSeries> identity,
            final long captureTimeMillis
    ) throws IOException {
        final Map<String, Long> latestByHost = new LinkedHashMap<>();
        for (MetricSeries series : identity) {
            final String host = tag(series, "host");
            if (host != null) {
                latestByHost.merge(host, series.timestamp(), Math::max);
            }
        }
        final Map<String, Long> freshByHost = new LinkedHashMap<>();
        latestByHost.forEach((host, timestamp) -> {
            if (Math.abs(captureTimeMillis - timestamp) <= ABSOLUTE_HOST_FRESHNESS_MILLIS) {
                freshByHost.put(host, timestamp);
            }
        });
        if (freshByHost.isEmpty()) {
            throw new IOException("Datadog did not return a recently active host for the automatically selected service");
        }
        final long newestTimestamp = freshByHost.values().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElseThrow();
        return freshByHost.entrySet().stream()
                .filter(entry -> newestTimestamp - entry.getValue() <= ACTIVE_HOST_FRESHNESS_MILLIS)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static String infrastructureSelector(
            final DatadogTargetInput target,
            final List<String> hosts
    ) {
        final String hostFilter = hosts.size() == 1
                ? "host:" + filterValue(hosts.getFirst())
                : "host IN (" + hosts.stream()
                        .map(DatadogEnvironmentClient::filterValue)
                        .collect(java.util.stream.Collectors.joining(",")) + ")";
        return "env:" + target.datadogEnv() + " AND " + hostFilter;
    }

    private static String filterValue(final String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("Datadog returned an unsupported host tag value");
        }
        return value;
    }
    private static String javaVersion(final MetricSeries identitySeries) {
        final String explicit = tag(identitySeries, "java_version");
        if (explicit != null) {
            return explicit;
        }
        return tagValues(identitySeries, "version").stream()
                .filter(value -> !"latest".equalsIgnoreCase(value))
                .filter(value -> !value.isEmpty() && Character.isDigit(value.charAt(0)))
                .findFirst()
                .orElse(null);
    }

    private static String commit(final MetricSeries identitySeries) {
        final String explicit = tag(identitySeries, "git.commit.sha");
        if (isCommitHash(explicit)) {
            return explicit;
        }
        return tagValues(identitySeries, "version").stream()
                .filter(DatadogEnvironmentClient::isCommitHash)
                .findFirst()
                .orElse(null);
    }

    private static boolean isCommitHash(final String value) {
        return value != null && value.matches("(?i)[0-9a-f]{7,64}");
    }

    private static List<String> tagValues(final MetricSeries series, final String key) {
        if (series == null) {
            return List.of();
        }
        final List<String> values = new ArrayList<>();
        for (String part : series.scope().split(",")) {
            final int separator = part.indexOf(':');
            if (separator <= 0 || !key.equals(part.substring(0, separator).trim())) {
                continue;
            }
            final String value = part.substring(separator + 1).trim();
            if (!value.isBlank() && !"N/A".equalsIgnoreCase(value)) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }
    private static Double maximumPositiveHeapValue(
            final List<MetricSeries> result,
            final String host
    ) {
        return result.stream()
                .filter(series -> host.equals(series.tags().get("host")))
                .filter(series -> "heap".equalsIgnoreCase(series.tags().get("area")))
                .map(MetricSeries::value)
                .filter(Objects::nonNull)
                .filter(value -> value > 0)
                .max(Double::compareTo)
                .orElse(null);
    }

    private static MetricSeries latestSeries(final List<MetricSeries> result, final String host) {
        return result.stream()
                .filter(series -> host.equals(series.tags().get("host")))
                .max(java.util.Comparator.comparingLong(MetricSeries::timestamp))
                .orElse(null);
    }

    private static Double latestValue(final List<MetricSeries> result, final String host) {
        return result.stream()
                .filter(series -> host.equals(series.tags().get("host")))
                .filter(series -> series.value() != null)
                .max(java.util.Comparator.comparingLong(MetricSeries::timestamp))
                .map(MetricSeries::value)
                .orElse(null);
    }

    private static Instant latestObservedAt(
            final List<List<MetricSeries>> results,
            final String host
    ) {
        return results.stream()
                .flatMap(List::stream)
                .filter(series -> host.equals(series.tags().get("host")))
                .mapToLong(MetricSeries::timestamp)
                .max()
                .stream()
                .mapToObj(Instant::ofEpochMilli)
                .findFirst()
                .orElse(null);
    }
    private static String inferRedisNetworkLocation(
            final List<MetricSeries> redisMaxMemory,
            final String coreHost
    ) {
        final boolean privateEndpointFound = redisMaxMemory.stream()
                .filter(series -> coreHost.equals(series.tags().get("host")))
                .map(series -> tag(series, "redis_host"))
                .filter(Objects::nonNull)
                .anyMatch(DatadogEnvironmentClient::isPrivateEndpoint);
        return privateEndpointFound
                ? "private endpoint observed on application host (Datadog inference)"
                : null;
    }

    private static boolean isPrivateEndpoint(final String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (!value.contains(".")) {
            return true;
        }
        if (value.startsWith("10.") || value.startsWith("192.168.")) {
            return true;
        }
        final Matcher matcher = Pattern.compile("^172\\.(\\d{1,2})\\.").matcher(value);
        if (!matcher.find()) {
            return false;
        }
        final int secondOctet = Integer.parseInt(matcher.group(1));
        return secondOctet >= 16 && secondOctet <= 31;
    }

    private static Boolean booleanTag(final MetricSeries series, final String key) {
        final String value = tag(series, key);
        if (value == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return null;
    }

    private static String tag(final MetricSeries series, final String key) {
        if (series == null) {
            return null;
        }
        final String value = series.tags().get(key);
        return value == null || value.isBlank() || "N/A".equalsIgnoreCase(value) ? null : value;
    }

    private static Double nanocoresToCpu(final Double value) {
        return value == null ? null : value / 1_000_000_000.0;
    }

    private static Integer toInteger(final Double value) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        final long rounded = Math.round(value);
        if (rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {
            return null;
        }
        return (int) rounded;
    }

    private static Long firstPositiveLong(final Double preferred, final Double fallback) {
        if (preferred != null && Double.isFinite(preferred) && preferred > 0) {
            return Math.round(preferred);
        }
        return toLong(fallback);
    }

    private static Long toLong(final Double value) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return Math.round(value);
    }

    private void requireCredentials() throws IOException {
        if (apiKey == null || appKey == null) {
            throw new IOException(
                    "Datadog credentials are not configured: set DATADOG_API_KEY and DATADOG_APP_KEY"
            );
        }
    }

    private static URI siteUri(final String configuredSite) {
        final String site = configuredSite == null || configuredSite.isBlank()
                ? "us5.datadoghq.com"
                : configuredSite.trim();
        final URI uri = URI.create(site.contains("://") ? site : "https://" + site);
        if (uri.getScheme() == null || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("DATADOG_SITE must be an HTTP(S) host");
        }
        return URI.create(uri.getScheme() + "://" + uri.getAuthority());
    }

    private static boolean retryable(final int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static MetricPoint latestPoint(final String seriesObject) {
        final Matcher matcher = POINT_PATTERN.matcher(seriesObject);
        MetricPoint latest = null;
        while (matcher.find()) {
            if ("null".equals(matcher.group(2))) {
                continue;
            }
            final double rawTimestamp = Double.parseDouble(matcher.group(1));
            final double rawValue = Double.parseDouble(matcher.group(2));
            if (!Double.isFinite(rawTimestamp) || !Double.isFinite(rawValue) || rawTimestamp < 0) {
                continue;
            }
            final MetricPoint candidate = new MetricPoint(Math.round(rawTimestamp), rawValue);
            if (latest == null || candidate.timestamp() > latest.timestamp()) {
                latest = candidate;
            }
        }
        return latest;
    }

    private static Map<String, String> parseScope(final String scope) {
        final Map<String, String> tags = new LinkedHashMap<>();
        for (String part : scope.split(",")) {
            final int separator = part.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            tags.put(part.substring(0, separator).trim(), part.substring(separator + 1).trim());
        }
        return Map.copyOf(tags);
    }

    private static int matchingDelimiter(
            final String value,
            final int start,
            final char open,
            final char close
    ) throws IOException {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            final char current = value.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == open) {
                depth++;
            } else if (current == close && --depth == 0) {
                return index;
            }
        }
        throw new IOException("Datadog response contains an unterminated JSON structure");
    }

    private static String stringValue(final String json, final String key) {
        final Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key)
                + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
        final Matcher matcher = pattern.matcher(json);
        return matcher.find() ? unescape(matcher.group(1)) : null;
    }

    private static String unescape(final String value) {
        return value.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private static String safeError(final Exception exception) {
        final String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        return message.length() <= 200 ? message : message.substring(0, 200);
    }

    private static String firstNonBlank(final String primary, final String fallback) {
        final String normalizedPrimary = trimToNull(primary);
        return normalizedPrimary == null ? trimToNull(fallback) : normalizedPrimary;
    }

    private static String trimToNull(final String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record MetricPoint(long timestamp, double value) {
    }
}

record MetricSeries(String scope, Map<String, String> tags, long timestamp, Double value) {
}

record DatadogRuntimeSnapshot(
        Instant observedAt,
        Instant identityObservedAt,
        String host,
        String containerId,
        Integer vcpu,
        Long ramBytes,
        Double containerCpuLimit,
        Long containerMemoryLimitBytes,
        Long jvmXmxBytes,
        String javaVersion,
        String commit,
        String imageName,
        String imageId,
        Integer tomcatMaxThreads,
        Integer tomcatMaxConnections,
        Integer hikariMaximumPoolSize,
        Long redisMaxMemoryBytes,
        String inferredRedisNetworkLocation,
        Boolean admissionTokenEnforcementEnabled,
        List<String> warnings
) {
    DatadogRuntimeSnapshot {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
