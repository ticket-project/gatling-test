package com.ticket.gatling.console;

import java.util.List;

public record RunEnvironmentInput(
        boolean captureEnabled,
        List<DatadogTargetInput> targets
) {
    public RunEnvironmentInput {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    static RunEnvironmentInput automatic(
            final SimulationType simulationType,
            final String baseUrl,
            final String coreBaseUrl,
            final String queueBaseUrl
    ) {
        final DatadogTargetInput queue = DatadogTargetInput.queue(
                simulationType.usesQueueBaseUrl() ? queueBaseUrl : baseUrl
        );
        final DatadogTargetInput core = DatadogTargetInput.core(coreBaseUrl);
        final List<DatadogTargetInput> targets = !simulationType.usesCoreBookingFlow()
                ? List.of(queue)
                : simulationType.usesQueueBaseUrl() ? List.of(queue, core) : List.of(core);
        // 로컬 대상에는 Datadog 에이전트가 없다. 캡처를 시도하면 실패 로그만 남으므로 아예 끈다.
        return new RunEnvironmentInput(!LoadTestRequest.isLocalUrl(coreBaseUrl), targets);
    }
}

record DatadogTargetInput(
        String role,
        String baseUrl,
        String datadogEnv,
        String datadogService,
        String datadogMetricPrefix,
        String datadogContainerName
) {
    DatadogTargetInput {
        role = required(role, "role");
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        datadogEnv = requiredTag(datadogEnv, "datadogEnv");
        datadogService = requiredTag(datadogService, "datadogService");
        datadogContainerName = requiredTag(datadogContainerName, "datadogContainerName");
        datadogMetricPrefix = required(datadogMetricPrefix, "datadogMetricPrefix");
        if (!datadogMetricPrefix.matches("[A-Za-z][A-Za-z0-9_.]*")) {
            throw new IllegalArgumentException("datadogMetricPrefix contains unsupported characters");
        }
    }

    static DatadogTargetInput queue(final String baseUrl) {
        return new DatadogTargetInput(
                "queue", baseUrl, "prod", "ticket-queue", "ticket_queue", "ticket-queue"
        );
    }

    static DatadogTargetInput core(final String baseUrl) {
        return new DatadogTargetInput(
                "core", baseUrl, "prod", "ticket-be", "ticket", "ticket-be"
        );
    }

    boolean core() {
        return "core".equals(role);
    }

    private static String requiredTag(final String value, final String name) {
        final String normalized = required(value, name);
        if (!normalized.matches("[A-Za-z0-9_./:-]+")) {
            throw new IllegalArgumentException(name + " contains unsupported Datadog tag characters");
        }
        return normalized;
    }

    private static String required(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
