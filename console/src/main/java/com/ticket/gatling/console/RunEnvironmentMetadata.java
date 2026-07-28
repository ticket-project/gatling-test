package com.ticket.gatling.console;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

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

                final Set<String> targetWarnings = new LinkedHashSet<>();
                runtimes.getFirst().warnings().forEach(targetWarnings::add);
                for (DatadogRuntimeSnapshot runtime : runtimes) {
                    addUnavailableWarnings(target, runtime, targetWarnings);
                }
                addHeterogeneousWarnings(target, runtimes, targetWarnings);
                targetWarnings.forEach(warning -> warnings.add(target.role() + ": " + warning));

                targetGroups.add(RuntimeTargetGroupMetadata.captured(
                        target,
                        runtimes.stream().map(RuntimeInstanceMetadata::from).toList(),
                        List.copyOf(targetWarnings)
                ));
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
            final List<RuntimeInstanceMetadata> instances = target.instances();
            if (instances.isEmpty()) {
                parts.add(role + "Env=failed");
                continue;
            }
            if (instances.size() > 1) {
                parts.add(role + "Instances=" + instances.size());
            }

            appendCommit(parts, role, instances);
            appendMachine(parts, role, instances);
            appendContainer(parts, role, instances);
            appendXmx(parts, role, instances);
            appendAdmission(parts, role, instances);
        }
        return String.join(",", parts);
    }

    String toJson(final UUID runId) {
        return "{\n"
                + "  \"schemaVersion\": 4,\n"
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
                        .collect(java.util.stream.Collectors.joining(",\n"))
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
                        .collect(java.util.stream.Collectors.joining(",\n"))
                + "\n      ]\n"
                + "    }";
    }

    private static String instanceJson(final RuntimeInstanceMetadata instance) {
        return "        {\n"
                + "          \"instanceKey\": " + Json.nullable(instanceKey(instance)) + ",\n"
                + "          \"host\": " + Json.nullable(nullIfBlank(instance.host())) + ",\n"
                + "          \"observedAt\": " + instant(instance.observedAt()) + ",\n"
                + "          \"identityObservedAt\": " + instant(instance.identityObservedAt()) + ",\n"
                + "          \"machine\": {\"vcpu\": " + number(instance.vcpu())
                + ", \"ramBytes\": " + number(instance.ramBytes()) + "},\n"
                + "          \"application\": {\"commit\": "
                + Json.nullable(nullIfBlank(instance.commit()))
                + ", \"javaVersion\": " + Json.nullable(nullIfBlank(instance.javaVersion()))
                + ", \"imageName\": " + Json.nullable(nullIfBlank(instance.imageName()))
                + ", \"imageId\": " + Json.nullable(nullIfBlank(instance.imageId())) + "},\n"
                + "          \"container\": {\"id\": " + Json.nullable(nullIfBlank(instance.containerId()))
                + ", \"cpuLimit\": " + number(instance.containerCpuLimit())
                + ", \"memoryLimitBytes\": " + number(instance.containerMemoryLimitBytes()) + "},\n"
                + "          \"jvm\": {\"xmsBytes\": null, \"xmxBytes\": "
                + number(instance.jvmXmxBytes()) + "},\n"
                + "          \"tomcat\": {\"maxThreads\": " + number(instance.tomcatMaxThreads())
                + ", \"maxConnections\": " + number(instance.tomcatMaxConnections()) + "},\n"
                + "          \"hikari\": {\"maximumPoolSize\": "
                + number(instance.hikariMaximumPoolSize()) + "},\n"
                + "          \"oracle\": {\"instanceProfile\": null, \"networkLocation\": null,"
                + " \"networkRttMs\": null},\n"
                + "          \"redis\": {\"instanceProfile\": null, \"networkLocation\": "
                + Json.nullable(nullIfBlank(instance.redisNetworkLocation()))
                + ", \"networkRttMs\": null, \"maxMemoryBytes\": "
                + number(instance.redisMaxMemoryBytes()) + "},\n"
                + "          \"features\": {\"admissionTokenEnforcementEnabled\": "
                + booleanValue(instance.admissionTokenEnforcementEnabled()) + "},\n"
                + "          \"evidence\": " + evidenceJson(instance) + "\n"
                + "        }";
    }

    private static String evidenceJson(final RuntimeInstanceMetadata instance) {
        return "{"
                + "\"machine.vcpu\": " + Json.nullable(evidence(instance.vcpu(), "not_reported"))
                + ", \"machine.ramBytes\": " + Json.nullable(evidence(instance.ramBytes(), "not_reported"))
                + ", \"application.commit\": " + Json.nullable(evidence(instance.commit(), "not_reported"))
                + ", \"application.javaVersion\": "
                + Json.nullable(evidence(instance.javaVersion(), "not_reported"))
                + ", \"application.imageName\": "
                + Json.nullable(evidence(instance.imageName(), "not_reported"))
                + ", \"application.imageId\": "
                + Json.nullable(evidence(instance.imageId(), "not_reported"))
                + ", \"container.id\": " + Json.nullable(evidence(instance.containerId(), "not_reported"))
                + ", \"container.cpuLimit\": "
                + Json.nullable(evidence(instance.containerCpuLimit(), "not_reported"))
                + ", \"container.memoryLimitBytes\": "
                + Json.nullable(evidence(instance.containerMemoryLimitBytes(), "not_reported"))
                + ", \"jvm.xmsBytes\": \"not_explicit\""
                + ", \"jvm.xmxBytes\": " + Json.nullable(evidence(instance.jvmXmxBytes(), "not_reported"))
                + ", \"tomcat.maxThreads\": "
                + Json.nullable(evidence(instance.tomcatMaxThreads(), "not_reported"))
                + ", \"tomcat.maxConnections\": "
                + Json.nullable(evidence(instance.tomcatMaxConnections(), "not_reported"))
                + ", \"hikari.maximumPoolSize\": "
                + Json.nullable(evidence(instance.hikariMaximumPoolSize(), "not_reported"))
                + ", \"oracle.instanceProfile\": \"unsupported_by_datadog\""
                + ", \"oracle.networkLocation\": \"unsupported_by_datadog\""
                + ", \"redis.maxMemoryBytes\": " + Json.nullable(redisMaxMemoryEvidence(instance))
                + ", \"redis.networkLocation\": "
                + Json.nullable(evidence(instance.redisNetworkLocation(), "not_reported"))
                + ", \"features.admissionTokenEnforcementEnabled\": "
                + Json.nullable(evidence(
                        instance.admissionTokenEnforcementEnabled(),
                        "unsupported_by_datadog"
                ))
                + "}";
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
        final String instance = runtime.host() + ": ";
        addUnavailable(warnings, runtime.vcpu() == null, instance + "not reported: machine.vcpu");
        addUnavailable(warnings, runtime.ramBytes() == null, instance + "not reported: machine.ramBytes");
        addUnavailable(warnings, !hasText(runtime.commit()), instance + "not reported: application.commit");
        addUnavailable(warnings, !hasText(runtime.javaVersion()), instance + "not reported: application.javaVersion");
        addUnavailable(warnings, !hasText(runtime.imageName()), instance + "not reported: application.imageName");
        addUnavailable(warnings, !hasText(runtime.imageId()), instance + "not reported: application.imageId");
        addUnavailable(warnings, !hasText(runtime.containerId()), instance + "not reported: container.id");
        addUnavailable(warnings, runtime.containerCpuLimit() == null, instance + "not reported: container.cpuLimit");
        addUnavailable(
                warnings,
                runtime.containerMemoryLimitBytes() == null,
                instance + "not reported: container.memoryLimitBytes"
        );
        warnings.add(instance + "not explicit: jvm.xmsBytes");
        addUnavailable(warnings, runtime.jvmXmxBytes() == null, instance + "not reported: jvm.xmxBytes");
        addUnavailable(warnings, runtime.tomcatMaxThreads() == null, instance + "not reported: tomcat.maxThreads");
        addUnavailable(
                warnings,
                runtime.tomcatMaxConnections() == null,
                instance + "not reported: tomcat.maxConnections"
        );
        addUnavailable(
                warnings,
                runtime.redisMaxMemoryBytes() == null,
                instance + "not reported: redis.maxMemoryBytes"
        );
        addUnavailable(
                warnings,
                !hasText(runtime.inferredRedisNetworkLocation()),
                instance + "not reported: redis.networkLocation"
        );
        if (target.core()) {
            addUnavailable(
                    warnings,
                    runtime.hikariMaximumPoolSize() == null,
                    instance + "not reported: hikari.maximumPoolSize"
            );
            warnings.add(instance + "unsupported by current Datadog telemetry: oracle.instanceProfile");
            warnings.add(instance + "unsupported by current Datadog telemetry: oracle.networkLocation");
            addUnavailable(
                    warnings,
                    runtime.admissionTokenEnforcementEnabled() == null,
                    instance + "unsupported by current Datadog telemetry: "
                            + "features.admissionTokenEnforcementEnabled"
            );
        }
    }

    private static void addHeterogeneousWarnings(
            final DatadogTargetInput target,
            final List<DatadogRuntimeSnapshot> runtimes,
            final Set<String> warnings
    ) {
        if (runtimes.size() < 2) {
            return;
        }
        addHeterogeneous(warnings, runtimes, DatadogRuntimeSnapshot::vcpu, "machine.vcpu");
        addHeterogeneous(warnings, runtimes, DatadogRuntimeSnapshot::ramBytes, "machine.ramBytes");
        addHeterogeneous(warnings, runtimes, DatadogRuntimeSnapshot::commit, "application.commit");
        addHeterogeneous(warnings, runtimes, DatadogRuntimeSnapshot::javaVersion, "application.javaVersion");
        addHeterogeneous(warnings, runtimes, DatadogRuntimeSnapshot::imageId, "application.imageId");
        addHeterogeneous(warnings, runtimes, DatadogRuntimeSnapshot::containerId, "container.id");
        addHeterogeneous(warnings, runtimes, DatadogRuntimeSnapshot::containerCpuLimit, "container.cpuLimit");
        addHeterogeneous(
                warnings,
                runtimes,
                DatadogRuntimeSnapshot::containerMemoryLimitBytes,
                "container.memoryLimitBytes"
        );
        addHeterogeneous(warnings, runtimes, DatadogRuntimeSnapshot::jvmXmxBytes, "jvm.xmxBytes");
        addHeterogeneous(warnings, runtimes, DatadogRuntimeSnapshot::tomcatMaxThreads, "tomcat.maxThreads");
        addHeterogeneous(
                warnings,
                runtimes,
                DatadogRuntimeSnapshot::tomcatMaxConnections,
                "tomcat.maxConnections"
        );
        if (target.core()) {
            addHeterogeneous(
                    warnings,
                    runtimes,
                    DatadogRuntimeSnapshot::hikariMaximumPoolSize,
                    "hikari.maximumPoolSize"
            );
            addHeterogeneous(
                    warnings,
                    runtimes,
                    DatadogRuntimeSnapshot::admissionTokenEnforcementEnabled,
                    "features.admissionTokenEnforcementEnabled"
            );
        }
    }

    private static <T> void addHeterogeneous(
            final Set<String> warnings,
            final List<DatadogRuntimeSnapshot> runtimes,
            final Function<DatadogRuntimeSnapshot, T> value,
            final String field
    ) {
        final T first = value.apply(runtimes.getFirst());
        if (runtimes.stream().skip(1).anyMatch(runtime -> !Objects.equals(first, value.apply(runtime)))) {
            warnings.add("heterogeneous across active hosts: " + field);
        }
    }

    private static void addUnavailable(
            final Set<String> warnings,
            final boolean unavailable,
            final String warning
    ) {
        if (unavailable) {
            warnings.add(warning);
        }
    }

    private static void appendCommit(
            final List<String> parts,
            final String role,
            final List<RuntimeInstanceMetadata> instances
    ) {
        if (!uniform(instances, RuntimeInstanceMetadata::commit)) {
            parts.add(role + "Commit=mixed");
            return;
        }
        final String commit = instances.getFirst().commit();
        if (hasText(commit) && !"unknown".equalsIgnoreCase(commit)) {
            parts.add(role + "Commit=" + compact(commit, 12));
        }
    }

    private static void appendMachine(
            final List<String> parts,
            final String role,
            final List<RuntimeInstanceMetadata> instances
    ) {
        if (!uniform(instances, RuntimeInstanceMetadata::vcpu)
                || !uniform(instances, RuntimeInstanceMetadata::ramBytes)) {
            parts.add(role + "=mixed");
            return;
        }
        final RuntimeInstanceMetadata first = instances.getFirst();
        if (first.vcpu() != null || first.ramBytes() != null) {
            final String count = instances.size() > 1 ? instances.size() + "x" : "";
            parts.add(role + "=" + count
                    + (first.vcpu() == null ? "unknown" : first.vcpu() + "vCPU")
                    + "/" + gibibytes(first.ramBytes()));
        }
    }

    private static void appendContainer(
            final List<String> parts,
            final String role,
            final List<RuntimeInstanceMetadata> instances
    ) {
        if (!uniform(instances, RuntimeInstanceMetadata::containerCpuLimit)
                || !uniform(instances, RuntimeInstanceMetadata::containerMemoryLimitBytes)) {
            parts.add(role + "Docker=mixed");
            return;
        }
        final RuntimeInstanceMetadata first = instances.getFirst();
        if (first.containerCpuLimit() != null || first.containerMemoryLimitBytes() != null) {
            parts.add(role + "Docker=" + decimal(first.containerCpuLimit())
                    + "CPU/" + gibibytes(first.containerMemoryLimitBytes()));
        }
    }

    private static void appendXmx(
            final List<String> parts,
            final String role,
            final List<RuntimeInstanceMetadata> instances
    ) {
        if (!uniform(instances, RuntimeInstanceMetadata::jvmXmxBytes)) {
            parts.add(role + "Xmx=mixed");
            return;
        }
        final Long xmx = instances.getFirst().jvmXmxBytes();
        if (xmx != null) {
            parts.add(role + "Xmx=" + gibibytes(xmx));
        }
    }

    private static void appendAdmission(
            final List<String> parts,
            final String role,
            final List<RuntimeInstanceMetadata> instances
    ) {
        if (!"core".equals(role)) {
            return;
        }
        if (!uniform(instances, RuntimeInstanceMetadata::admissionTokenEnforcementEnabled)) {
            parts.add("admission=mixed");
            return;
        }
        final Boolean admission = instances.getFirst().admissionTokenEnforcementEnabled();
        if (admission != null) {
            parts.add("admission=" + admission);
        }
    }

    private static <T> boolean uniform(
            final List<RuntimeInstanceMetadata> instances,
            final Function<RuntimeInstanceMetadata, T> value
    ) {
        final T first = value.apply(instances.getFirst());
        return instances.stream().skip(1).allMatch(instance -> Objects.equals(first, value.apply(instance)));
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

    private static String instanceKey(final RuntimeInstanceMetadata instance) {
        return hasText(instance.containerId()) ? instance.containerId() : nullIfBlank(instance.host());
    }

    private static String evidence(final Object value, final String unavailableStatus) {
        return value == null || value instanceof String string && string.isBlank()
                ? unavailableStatus
                : "observed";
    }

    private static String redisMaxMemoryEvidence(final RuntimeInstanceMetadata instance) {
        if (instance.redisMaxMemoryBytes() == null) {
            return "not_reported";
        }
        return instance.redisMaxMemoryBytes() == 0L ? "explicit_unlimited" : "observed";
    }

    private static String number(final Number value) {
        return value == null ? "null" : value.toString();
    }

    private static String booleanValue(final Boolean value) {
        return value == null ? "null" : value.toString();
    }

    private static String instant(final Instant value) {
        return value == null ? "null" : Json.nullable(value.toString());
    }

    private static String stringArray(final List<String> values) {
        return values.stream()
                .map(Json::nullable)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
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
        List<RuntimeInstanceMetadata> instances
) {
    RuntimeTargetGroupMetadata {
        captureWarnings = captureWarnings == null ? List.of() : List.copyOf(captureWarnings);
        instances = instances == null ? List.of() : List.copyOf(instances);
    }

    static RuntimeTargetGroupMetadata captured(
            final DatadogTargetInput target,
            final List<RuntimeInstanceMetadata> instances,
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

record RuntimeInstanceMetadata(
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
        String redisNetworkLocation,
        Boolean admissionTokenEnforcementEnabled
) {
    static RuntimeInstanceMetadata from(final DatadogRuntimeSnapshot runtime) {
        return new RuntimeInstanceMetadata(
                runtime.observedAt(),
                runtime.identityObservedAt(),
                runtime.host(),
                runtime.containerId(),
                runtime.vcpu(),
                runtime.ramBytes(),
                runtime.containerCpuLimit(),
                runtime.containerMemoryLimitBytes(),
                runtime.jvmXmxBytes(),
                runtime.javaVersion(),
                runtime.commit(),
                runtime.imageName(),
                runtime.imageId(),
                runtime.tomcatMaxThreads(),
                runtime.tomcatMaxConnections(),
                runtime.hikariMaximumPoolSize(),
                runtime.redisMaxMemoryBytes(),
                runtime.inferredRedisNetworkLocation(),
                runtime.admissionTokenEnforcementEnabled()
        );
    }
}
