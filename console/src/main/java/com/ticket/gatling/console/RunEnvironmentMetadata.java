package com.ticket.gatling.console;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public record RunEnvironmentMetadata(
        Instant capturedAt,
        String captureStatus,
        String captureSource,
        String captureError,
        List<String> captureWarnings,
        List<RuntimeTargetGroupMetadata> targets
) {
    private static final String CAPTURE_SOURCE = "datadog";
    private static final String CAPTURE_PHASE = "preRun";
    private static final String NOT_REPORTED = "not_reported";

    /** 인스턴스별로 기록하는 값. path의 점 앞은 JSON 그룹, 뒤는 키다. */
    record Field(
            String path,
            Function<DatadogRuntimeSnapshot, Object> getter,
            String missingStatus,
            boolean coreOnly
    ) {
        String group() {
            return path.substring(0, path.indexOf('.'));
        }

        String key() {
            return path.substring(path.indexOf('.') + 1);
        }
    }

    static final List<Field> FIELDS = List.of(
            new Field("machine.vcpu", DatadogRuntimeSnapshot::vcpu, NOT_REPORTED, false),
            new Field("machine.ramBytes", DatadogRuntimeSnapshot::ramBytes, NOT_REPORTED, false),
            new Field("application.commit", DatadogRuntimeSnapshot::commit, NOT_REPORTED, false),
            new Field("application.javaVersion", DatadogRuntimeSnapshot::javaVersion, NOT_REPORTED, false),
            new Field("application.imageName", DatadogRuntimeSnapshot::imageName, NOT_REPORTED, false),
            new Field("application.imageId", DatadogRuntimeSnapshot::imageId, NOT_REPORTED, false),
            new Field("container.id", DatadogRuntimeSnapshot::containerId, NOT_REPORTED, false),
            new Field("container.cpuLimit", DatadogRuntimeSnapshot::containerCpuLimit, NOT_REPORTED, false),
            new Field("container.memoryLimitBytes", DatadogRuntimeSnapshot::containerMemoryLimitBytes, NOT_REPORTED, false),
            new Field("jvm.xmxBytes", DatadogRuntimeSnapshot::jvmXmxBytes, NOT_REPORTED, false),
            new Field("tomcat.maxThreads", DatadogRuntimeSnapshot::tomcatMaxThreads, NOT_REPORTED, false),
            new Field("tomcat.maxConnections", DatadogRuntimeSnapshot::tomcatMaxConnections, NOT_REPORTED, false),
            new Field("hikari.maximumPoolSize", DatadogRuntimeSnapshot::hikariMaximumPoolSize, NOT_REPORTED, true),
            new Field("redis.maxMemoryBytes", DatadogRuntimeSnapshot::redisMaxMemoryBytes, NOT_REPORTED, false),
            new Field("redis.networkLocation", DatadogRuntimeSnapshot::inferredRedisNetworkLocation, NOT_REPORTED, false),
            new Field("features.admissionTokenEnforcementEnabled",
                    DatadogRuntimeSnapshot::admissionTokenEnforcementEnabled, "unsupported_by_datadog", true)
    );

    public RunEnvironmentMetadata {
        captureWarnings = captureWarnings == null ? List.of() : List.copyOf(captureWarnings);
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    static RunEnvironmentMetadata capture(
            final LoadTestRequest request,
            final RunEnvironmentClient client
    ) {
        final RunEnvironmentInput input = request.environment();
        if (!input.captureEnabled()) {
            return new RunEnvironmentMetadata(
                    Instant.now(), "disabled", CAPTURE_SOURCE, null, List.of(), List.of()
            );
        }

        final List<RuntimeTargetGroupMetadata> targetGroups = new ArrayList<>();
        final Set<String> warnings = new LinkedHashSet<>();
        final List<String> errors = new ArrayList<>();
        int capturedTargetCount = 0;
        for (int index = 0; index < input.targets().size(); index++) {
            final DatadogTargetInput target = input.targets().get(index);
            try {
                final List<DatadogRuntimeSnapshot> runtimes = client.capture(target);
                validateRuntimes(runtimes);

                final Set<String> targetWarnings = new LinkedHashSet<>(runtimes.getFirst().warnings());
                for (DatadogRuntimeSnapshot runtime : runtimes) {
                    addUnavailableWarnings(target, runtime, targetWarnings);
                }
                addHeterogeneousWarnings(target, runtimes, targetWarnings);
                targetWarnings.forEach(warning -> warnings.add(target.role() + ": " + warning));

                targetGroups.add(RuntimeTargetGroupMetadata.captured(target, runtimes, List.copyOf(targetWarnings)));
                capturedTargetCount++;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                final String error = "Datadog capture was interrupted";
                errors.add(target.role() + ": " + error);
                targetGroups.add(RuntimeTargetGroupMetadata.failed(target, error));
                for (int skipped = index + 1; skipped < input.targets().size(); skipped++) {
                    final DatadogTargetInput skippedTarget = input.targets().get(skipped);
                    final String skippedError = "not attempted because Datadog capture was interrupted";
                    errors.add(skippedTarget.role() + ": " + skippedError);
                    targetGroups.add(RuntimeTargetGroupMetadata.failed(skippedTarget, skippedError));
                }
                break;
            } catch (Exception exception) {
                final String error = safeError(exception);
                errors.add(target.role() + ": " + error);
                targetGroups.add(RuntimeTargetGroupMetadata.failed(target, error));
            }
        }

        final String status;
        if (capturedTargetCount == 0) {
            status = "failed";
        } else if (capturedTargetCount < input.targets().size()) {
            status = "partial";
        } else {
            status = "captured";
        }
        return new RunEnvironmentMetadata(
                Instant.now(),
                status,
                CAPTURE_SOURCE,
                errors.isEmpty() ? null : String.join("; ", errors),
                List.copyOf(warnings),
                targetGroups
        );
    }

    String runDescription(final UUID runId) {
        final List<String> parts = new ArrayList<>();
        parts.add("runId=" + runId.toString().substring(0, 8));
        for (RuntimeTargetGroupMetadata target : targets) {
            final String role = target.role();
            final List<DatadogRuntimeSnapshot> instances = target.instances();
            if (instances.isEmpty()) {
                parts.add(role + "Env=failed");
                continue;
            }
            final String count = instances.size() > 1 ? instances.size() + "x" : "";
            if (instances.size() > 1) {
                parts.add(role + "Instances=" + instances.size());
            }
            describe(parts, role + "Commit", instances, instance ->
                    hasText(instance.commit()) && !"unknown".equalsIgnoreCase(instance.commit())
                            ? compact(instance.commit(), 12) : null);
            describe(parts, role, instances, instance ->
                    instance.vcpu() == null && instance.ramBytes() == null ? null
                            : count + (instance.vcpu() == null ? "unknown" : instance.vcpu() + "vCPU")
                            + "/" + gibibytes(instance.ramBytes()));
            describe(parts, role + "Docker", instances, instance ->
                    instance.containerCpuLimit() == null && instance.containerMemoryLimitBytes() == null ? null
                            : decimal(instance.containerCpuLimit()) + "CPU/"
                            + gibibytes(instance.containerMemoryLimitBytes()));
            describe(parts, role + "Xmx", instances, instance ->
                    instance.jvmXmxBytes() == null ? null : gibibytes(instance.jvmXmxBytes()));
            if ("core".equals(role)) {
                describe(parts, "admission", instances, instance ->
                        instance.admissionTokenEnforcementEnabled() == null ? null
                                : instance.admissionTokenEnforcementEnabled().toString());
            }
        }
        return String.join(",", parts);
    }

    /** 모든 인스턴스가 같은 값이면 key=value, 다르면 key=mixed, 값이 없으면 생략. */
    private static void describe(
            final List<String> parts,
            final String key,
            final List<DatadogRuntimeSnapshot> instances,
            final Function<DatadogRuntimeSnapshot, String> format
    ) {
        if (instances.stream().map(format).distinct().count() > 1) {
            parts.add(key + "=mixed");
            return;
        }
        final String value = format.apply(instances.getFirst());
        if (value != null) {
            parts.add(key + "=" + value);
        }
    }

    String toJson(final UUID runId) {
        return "{\n"
                + "  \"schemaVersion\": 5,\n"
                + "  \"runId\": \"" + runId + "\",\n"
                + "  \"capturedAt\": \"" + capturedAt + "\",\n"
                + "  \"capturePhase\": \"" + CAPTURE_PHASE + "\",\n"
                + "  \"capture\": {\"status\": " + Json.nullable(captureStatus)
                + ", \"source\": " + Json.nullable(captureSource)
                + ", \"error\": " + Json.nullable(nullIfBlank(captureError))
                + ", \"warnings\": " + stringArray(captureWarnings) + "},\n"
                + "  \"targets\": [\n"
                + targets.stream()
                        .map(RunEnvironmentMetadata::targetJson)
                        .collect(Collectors.joining(",\n"))
                + "\n  ]\n"
                + "}";
    }

    private static String targetJson(final RuntimeTargetGroupMetadata target) {
        return "    {\n"
                + "      \"role\": " + Json.nullable(target.role()) + ",\n"
                + "      \"baseUrl\": " + Json.nullable(nullIfBlank(target.baseUrl())) + ",\n"
                + "      \"capture\": {\"status\": " + Json.nullable(target.captureStatus())
                + ", \"error\": " + Json.nullable(nullIfBlank(target.captureError()))
                + ", \"warnings\": " + stringArray(target.captureWarnings()) + "},\n"
                + "      \"datadog\": {\"env\": " + Json.nullable(target.datadogEnv())
                + ", \"service\": " + Json.nullable(target.datadogService())
                + ", \"metricPrefix\": " + Json.nullable(target.datadogMetricPrefix())
                + ", \"containerName\": " + Json.nullable(target.datadogContainerName())
                + ", \"granularity\": \"host\"},\n"
                + "      \"replicaCountObserved\": " + target.instances().size() + ",\n"
                + "      \"replicaCountSemantics\": \"distinct fresh hosts\",\n"
                + "      \"granularityNote\": \"Application metrics are grouped by host;"
                + " multiple JVM replicas on one host require container_id or pod tags to separate.\",\n"
                + "      \"instances\": [\n"
                + target.instances().stream()
                        .map(RunEnvironmentMetadata::instanceJson)
                        .collect(Collectors.joining(",\n"))
                + "\n      ]\n"
                + "    }";
    }

    private static String instanceJson(final DatadogRuntimeSnapshot instance) {
        final Map<String, List<Field>> groups = FIELDS.stream()
                .collect(Collectors.groupingBy(Field::group, LinkedHashMap::new, Collectors.toList()));
        final StringBuilder json = new StringBuilder("        {\n")
                .append("          \"instanceKey\": ").append(Json.nullable(instanceKey(instance))).append(",\n")
                .append("          \"host\": ").append(Json.nullable(nullIfBlank(instance.host()))).append(",\n")
                .append("          \"observedAt\": ").append(instant(instance.observedAt())).append(",\n")
                .append("          \"identityObservedAt\": ").append(instant(instance.identityObservedAt())).append(",\n");
        groups.forEach((group, fields) -> json.append("          \"").append(group).append("\": ")
                .append(fields.stream()
                        .map(field -> "\"" + field.key() + "\": " + jsonValue(field.getter().apply(instance)))
                        .collect(Collectors.joining(", ", "{", "}")))
                .append(",\n"));
        return json.append("          \"evidence\": ")
                .append(FIELDS.stream()
                        .map(field -> "\"" + field.path() + "\": " + Json.nullable(evidence(field, instance)))
                        .collect(Collectors.joining(", ", "{", "}")))
                .append("\n        }")
                .toString();
    }

    private static String evidence(final Field field, final DatadogRuntimeSnapshot instance) {
        final Object value = field.getter().apply(instance);
        if (!hasValue(value)) {
            return field.missingStatus();
        }
        return "redis.maxMemoryBytes".equals(field.path()) && Long.valueOf(0L).equals(value)
                ? "explicit_unlimited"
                : "observed";
    }

    private static void validateRuntimes(final List<DatadogRuntimeSnapshot> runtimes) {
        if (runtimes == null || runtimes.isEmpty()) {
            throw new IllegalStateException("Datadog capture returned no active runtime instances");
        }
        final Set<String> hosts = new LinkedHashSet<>();
        for (DatadogRuntimeSnapshot runtime : runtimes) {
            if (runtime == null || !hasText(runtime.host())) {
                throw new IllegalStateException("Datadog capture returned an instance without a host identity");
            }
            if (!hosts.add(runtime.host())) {
                throw new IllegalStateException("Datadog capture returned a duplicate host identity");
            }
        }
    }

    private static void addUnavailableWarnings(
            final DatadogTargetInput target,
            final DatadogRuntimeSnapshot runtime,
            final Set<String> warnings
    ) {
        for (Field field : FIELDS) {
            if ((!field.coreOnly() || target.core()) && !hasValue(field.getter().apply(runtime))) {
                warnings.add(runtime.host() + ": " + field.missingStatus().replace('_', ' ') + ": " + field.path());
            }
        }
    }

    private static void addHeterogeneousWarnings(
            final DatadogTargetInput target,
            final List<DatadogRuntimeSnapshot> runtimes,
            final Set<String> warnings
    ) {
        for (Field field : FIELDS) {
            if ((!field.coreOnly() || target.core())
                    && runtimes.stream().map(field.getter()).distinct().count() > 1) {
                warnings.add("heterogeneous across active hosts: " + field.path());
            }
        }
    }

    static String sanitizeBaseUrl(final String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            final URI uri = new URI(value.trim());
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeError(final Exception exception) {
        final String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    private static String compact(final String value, final int length) {
        final String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "-");
        return sanitized.length() <= length ? sanitized : sanitized.substring(0, length);
    }

    private static String gibibytes(final Long bytes) {
        if (bytes == null) {
            return "unknown";
        }
        final double gib = bytes / 1_073_741_824.0;
        return Math.abs(gib - Math.rint(gib)) < 0.05
                ? String.format(Locale.ROOT, "%.0fGiB", gib)
                : String.format(Locale.ROOT, "%.1fGiB", gib);
    }

    private static String decimal(final Double value) {
        if (value == null) {
            return "unknown";
        }
        return Math.abs(value - Math.rint(value)) < 0.001
                ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.2f", value);
    }

    private static String instanceKey(final DatadogRuntimeSnapshot instance) {
        return hasText(instance.containerId()) ? instance.containerId() : nullIfBlank(instance.host());
    }

    private static String jsonValue(final Object value) {
        if (value instanceof String string) {
            return Json.nullable(nullIfBlank(string));
        }
        return value == null ? "null" : value.toString();
    }

    private static boolean hasValue(final Object value) {
        return value != null && !(value instanceof String string && string.isBlank());
    }

    private static String instant(final Instant value) {
        return value == null ? "null" : Json.nullable(value.toString());
    }

    private static String stringArray(final List<String> values) {
        return values.stream()
                .map(Json::nullable)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String nullIfBlank(final String value) {
        return hasText(value) ? value : null;
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}

record RuntimeTargetGroupMetadata(
        String role,
        String baseUrl,
        String captureStatus,
        String captureError,
        List<String> captureWarnings,
        String datadogEnv,
        String datadogService,
        String datadogMetricPrefix,
        String datadogContainerName,
        List<DatadogRuntimeSnapshot> instances
) {
    RuntimeTargetGroupMetadata {
        captureWarnings = captureWarnings == null ? List.of() : List.copyOf(captureWarnings);
        instances = instances == null ? List.of() : List.copyOf(instances);
    }

    static RuntimeTargetGroupMetadata captured(
            final DatadogTargetInput target,
            final List<DatadogRuntimeSnapshot> instances,
            final List<String> warnings
    ) {
        return new RuntimeTargetGroupMetadata(
                target.role(),
                RunEnvironmentMetadata.sanitizeBaseUrl(target.baseUrl()),
                "captured",
                null,
                warnings,
                target.datadogEnv(),
                target.datadogService(),
                target.datadogMetricPrefix(),
                target.datadogContainerName(),
                instances
        );
    }

    static RuntimeTargetGroupMetadata failed(final DatadogTargetInput target, final String error) {
        return new RuntimeTargetGroupMetadata(
                target.role(),
                RunEnvironmentMetadata.sanitizeBaseUrl(target.baseUrl()),
                "failed",
                error,
                List.of(),
                target.datadogEnv(),
                target.datadogService(),
                target.datadogMetricPrefix(),
                target.datadogContainerName(),
                List.of()
        );
    }
}
