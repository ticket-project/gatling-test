package com.ticket.gatling.console;

public record TargetEnvironment(
        String key,
        String label,
        String coreBaseUrl,
        String queueBaseUrl
) {
    public TargetEnvironment {
        key = key == null ? "" : key.trim();
        label = label == null || label.isBlank() ? key : label.trim();
        coreBaseUrl = coreBaseUrl == null ? "" : coreBaseUrl.trim();
        queueBaseUrl = queueBaseUrl == null ? "" : queueBaseUrl.trim();
    }
}
