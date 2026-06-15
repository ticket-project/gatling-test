package com.ticket.gatling.console;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record LoadTestRequest(
        Path ticketProjectPath,
        SimulationType simulationType,
        String baseUrl,
        String performanceId,
        String seatIds,
        int users,
        int durationSeconds,
        String injectionMode,
        double usersPerSecond,
        double targetUsersPerSecond,
        int statusPolls,
        int statusPollPauseSeconds,
        String accessTokenMode,
        String loginEmailPrefix,
        String loginEmailDomain,
        String loginPassword,
        int loginStartIndex,
        int loginTimeoutSeconds,
        int seedMemberCount,
        String jwtSecret,
        String jwtIssuer,
        long syntheticMemberStartId,
        String syntheticJwtRole,
        int syntheticTokenTtlSeconds,
        String admissionTokenMode,
        String admissionTokenIssuer,
        String admissionTokenAudience,
        String admissionTokenSecret,
        int admissionTokenTtlSeconds,
        String accessTokens,
        String admissionTokens
) {
    private static final String DEFAULT_TICKET_PATH = "C:\\Users\\mn040\\IdeaProjects\\ticket-workspace\\gatling-test";
    private static final String SYNTHETIC_JWT_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String SYNTHETIC_ADMISSION_SECRET = "0123456789abcdef0123456789abcdef";

    public LoadTestRequest {
        if (users <= 0) {
            throw new IllegalArgumentException("users must be positive");
        }
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        if (loginStartIndex <= 0) {
            throw new IllegalArgumentException("loginStartIndex must be positive");
        }
        if (seedMemberCount <= 0) {
            throw new IllegalArgumentException("seedMemberCount must be positive");
        }
        if (syntheticMemberStartId <= 0) {
            throw new IllegalArgumentException("syntheticMemberStartId must be positive");
        }
        if (syntheticTokenTtlSeconds <= 0) {
            throw new IllegalArgumentException("syntheticTokenTtlSeconds must be positive");
        }
        if (admissionTokenTtlSeconds <= 0) {
            throw new IllegalArgumentException("admissionTokenTtlSeconds must be positive");
        }
        ticketProjectPath = ticketProjectPath.toAbsolutePath().normalize();
        baseUrl = defaultIfBlank(baseUrl, simulationType.defaultBaseUrl());
        performanceId = defaultIfBlank(performanceId, "1");
        seatIds = defaultIfBlank(seatIds, "1");
        injectionMode = defaultIfBlank(injectionMode, "ramp-users");
        accessTokenMode = normalizeAccessTokenMode(accessTokenMode);
        loginEmailPrefix = defaultIfBlank(loginEmailPrefix, "loadtest");
        loginEmailDomain = defaultIfBlank(loginEmailDomain, "test.com");
        loginPassword = defaultIfBlank(loginPassword, "password1234");
        jwtSecret = "synthetic-jwt".equals(accessTokenMode)
                ? SYNTHETIC_JWT_SECRET
                : (jwtSecret == null ? "" : jwtSecret.trim());
        jwtIssuer = defaultIfBlank(jwtIssuer, "ticket");
        syntheticJwtRole = defaultIfBlank(syntheticJwtRole, "MEMBER");
        admissionTokenMode = normalizeAdmissionTokenMode(admissionTokenMode);
        admissionTokenIssuer = defaultIfBlank(admissionTokenIssuer, "ticket-queue");
        admissionTokenAudience = defaultIfBlank(admissionTokenAudience, "ticket-api");
        admissionTokenSecret = defaultIfBlank(admissionTokenSecret, SYNTHETIC_ADMISSION_SECRET);
        accessTokens = accessTokens == null ? "" : accessTokens.trim();
        admissionTokens = admissionTokens == null ? "" : admissionTokens.trim();
    }

    public static LoadTestRequest fromForm(final Map<String, List<String>> form) {
        return new LoadTestRequest(
                Path.of(value(form, "ticketProjectPath", DEFAULT_TICKET_PATH)),
                SimulationType.fromKey(value(form, "simulation", SimulationType.QUEUE_ENTER.key())),
                value(form, "baseUrl", ""),
                value(form, "performanceId", "1"),
                value(form, "seatIds", "1"),
                intValue(form, "users", 10),
                intValue(form, "durationSeconds", 10),
                value(form, "injectionMode", "ramp-users"),
                doubleValue(form, "usersPerSecond", 1.0),
                doubleValue(form, "targetUsersPerSecond", 10.0),
                intValue(form, "statusPolls", 3),
                intValue(form, "statusPollPauseSeconds", 1),
                value(form, "accessTokenMode", "login"),
                value(form, "loginEmailPrefix", "loadtest"),
                value(form, "loginEmailDomain", "test.com"),
                value(form, "loginPassword", "password1234"),
                intValue(form, "loginStartIndex", 1),
                intValue(form, "loginTimeoutSeconds", 5),
                intValue(form, "seedMemberCount", 100),
                value(form, "jwtSecret", SYNTHETIC_JWT_SECRET),
                value(form, "jwtIssuer", "ticket"),
                longValue(form, "syntheticMemberStartId", 1L),
                value(form, "syntheticJwtRole", "MEMBER"),
                intValue(form, "syntheticTokenTtlSeconds", 3600),
                value(form, "admissionTokenMode", "synthetic"),
                value(form, "admissionTokenIssuer", "ticket-queue"),
                value(form, "admissionTokenAudience", "ticket-api"),
                value(form, "admissionTokenSecret", SYNTHETIC_ADMISSION_SECRET),
                intValue(form, "admissionTokenTtlSeconds", 300),
                value(form, "accessTokens", ""),
                value(form, "admissionTokens", "")
        );
    }

    public Path reportsRoot() {
        return ticketProjectPath.resolve("load-tests").resolve("gatling")
                .resolve("build").resolve("reports").resolve("gatling");
    }

    public int estimatedVirtualUsers() {
        return switch (injectionMode) {
            case "constant-users-per-sec" -> (int) Math.ceil(usersPerSecond * durationSeconds);
            case "ramp-users-per-sec" -> (int) Math.ceil(((usersPerSecond + targetUsersPerSecond) / 2.0) * durationSeconds);
            default -> users;
        };
    }

    private static String value(
            final Map<String, List<String>> form,
            final String key,
            final String defaultValue
    ) {
        final List<String> values = form.get(key);
        if (values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) {
            return defaultValue;
        }
        return values.getFirst().trim();
    }

    private static int intValue(
            final Map<String, List<String>> form,
            final String key,
            final int defaultValue
    ) {
        return Integer.parseInt(value(form, key, String.valueOf(defaultValue)));
    }

    private static double doubleValue(
            final Map<String, List<String>> form,
            final String key,
            final double defaultValue
    ) {
        return Double.parseDouble(value(form, key, String.valueOf(defaultValue)));
    }

    private static long longValue(
            final Map<String, List<String>> form,
            final String key,
            final long defaultValue
    ) {
        return Long.parseLong(value(form, key, String.valueOf(defaultValue)));
    }

    private static String defaultIfBlank(final String value, final String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static String normalizeAccessTokenMode(final String value) {
        final String mode = defaultIfBlank(value, "login").toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("login") || mode.equals("tokens") || mode.equals("synthetic-jwt")) {
            return mode;
        }
        throw new IllegalArgumentException("Unsupported accessTokenMode: " + mode);
    }

    private static String normalizeAdmissionTokenMode(final String value) {
        final String mode = defaultIfBlank(value, "synthetic").toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("synthetic") || mode.equals("tokens")) {
            return mode;
        }
        throw new IllegalArgumentException("Unsupported admissionTokenMode: " + mode);
    }
}
