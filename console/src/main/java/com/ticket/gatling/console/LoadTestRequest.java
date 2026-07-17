package com.ticket.gatling.console;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record LoadTestRequest(
        Path ticketProjectPath,
        SimulationType simulationType,
        String baseUrl,
        String coreBaseUrl,
        String queueBaseUrl,
        String performanceId,
        String seatIds,
        int users,
        int durationSeconds,
        String injectionMode,
        double usersPerSecond,
        double targetUsersPerSecond,
        String executionMode,
        boolean distributedIncludeLocal,
        boolean distributedCollectReports,
        boolean distributedDumpFailureBody,
        int distributedDumpFailureBodyLimit,
        String distributedHosts,
        Path sshKeyPath,
        String distributedRemoteProjectDir,
        int statusPolls,
        int statusPollPauseSeconds,
        int statusPollPauseJitterSeconds,
        int pollingTimeoutSeconds,
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
        String accessTokenSource,
        String accessTokensFile,
        int generatedAccessTokenCount,
        String admissionTokens,
        String bookingFeederFile,
        String bookingScenario,
        int nodeIndex,
        String resultFile,
        boolean operationalConfirmation
) {
    private static final String DEFAULT_LOAD_TESTS_PATH =
            "C:\\Users\\mn040\\IdeaProjects\\ticket-workspace\\gatling-test";
    private static final String SSH_KEY_FILE_NAME = "ticket-test-key-01.pem";
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
        if (statusPollPauseSeconds < 0) {
            throw new IllegalArgumentException("statusPollPauseSeconds must be non-negative");
        }
        if (statusPollPauseJitterSeconds < 0) {
            throw new IllegalArgumentException("statusPollPauseJitterSeconds must be non-negative");
        }
        if (pollingTimeoutSeconds < 0) {
            throw new IllegalArgumentException("pollingTimeoutSeconds must be non-negative");
        }
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("nodeIndex must be non-negative");
        }
        if (distributedDumpFailureBodyLimit <= 0) {
            throw new IllegalArgumentException("distributedDumpFailureBodyLimit must be positive");
        }
        ticketProjectPath = ticketProjectPath.toAbsolutePath().normalize();
        sshKeyPath = sshKeyPath.toAbsolutePath().normalize();
        baseUrl = defaultIfBlank(baseUrl, simulationType.defaultBaseUrl());
        coreBaseUrl = defaultIfBlank(coreBaseUrl, baseUrl);
        queueBaseUrl = defaultIfBlank(queueBaseUrl, baseUrl);
        performanceId = defaultIfBlank(performanceId, "1");
        seatIds = defaultIfBlank(seatIds, "1");
        injectionMode = defaultIfBlank(injectionMode, "ramp-users");
        executionMode = normalizeExecutionMode(executionMode);
        distributedHosts = defaultIfBlank(distributedHosts, defaultDistributedHosts());
        distributedRemoteProjectDir = defaultIfBlank(distributedRemoteProjectDir, "~/gatling-test");
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
        accessTokenSource = normalizeAccessTokenSource(accessTokenSource);
        if ("inline".equals(accessTokenSource) && accessTokens.isBlank()) {
            accessTokenSource = "generate-file";
        }
        accessTokensFile = defaultIfBlank(accessTokensFile, defaultAccessTokensFile(ticketProjectPath));
        generatedAccessTokenCount = generatedAccessTokenCount <= 0
                ? estimateVirtualUsers(users, durationSeconds, injectionMode, usersPerSecond, targetUsersPerSecond)
                : generatedAccessTokenCount;
        admissionTokens = admissionTokens == null ? "" : admissionTokens.trim();
        bookingFeederFile = defaultIfBlank(bookingFeederFile, "build/booking-feeder.csv");
        bookingScenario = defaultIfBlank(bookingScenario, simulationType.bookingScenario());
        resultFile = defaultIfBlank(resultFile, "build/reports/booking-results.csv");
    }

    public static LoadTestRequest fromForm(final Map<String, List<String>> form) {
        final Path ticketProjectPath = Path.of(value(form, "ticketProjectPath", DEFAULT_LOAD_TESTS_PATH));
        final Path sshKeyPath = Path.of(value(form, "sshKeyPath", defaultSshKeyPath()));
        final String accessTokens = value(form, "accessTokens", "");
        final String accessTokensFile = value(form, "accessTokensFile", "");
        return new LoadTestRequest(
                ticketProjectPath,
                SimulationType.fromKey(value(form, "simulation", SimulationType.QUEUE_ENTER.key())),
                value(form, "baseUrl", ""),
                value(form, "coreBaseUrl", ""),
                value(form, "queueBaseUrl", ""),
                value(form, "performanceId", "1"),
                value(form, "seatIds", "1"),
                intValue(form, "users", 10),
                intValue(form, "durationSeconds", 10),
                value(form, "injectionMode", "ramp-users"),
                doubleValue(form, "usersPerSecond", 1.0),
                doubleValue(form, "targetUsersPerSecond", 10.0),
                value(form, "executionMode", "local"),
                booleanValue(form, "distributedIncludeLocal", false),
                booleanValue(form, "distributedCollectReports", true),
                booleanValue(form, "distributedDumpFailureBody", false),
                intValue(form, "distributedDumpFailureBodyLimit", 1),
                value(form, "distributedHosts", defaultDistributedHosts()),
                sshKeyPath,
                value(form, "distributedRemoteProjectDir", "~/gatling-test"),
                intValue(form, "statusPolls", 3),
                intValue(form, "statusPollPauseSeconds", 1),
                intValue(form, "statusPollPauseJitterSeconds", 0),
                intValue(form, "pollingTimeoutSeconds", 300),
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
                accessTokens,
                value(form, "accessTokenSource", defaultAccessTokenSource(accessTokens, accessTokensFile)),
                accessTokensFile,
                intValue(form, "generatedAccessTokenCount", 0),
                value(form, "admissionTokens", ""),
                value(form, "bookingFeederFile", ""),
                value(form, "bookingScenario", ""),
                intValue(form, "nodeIndex", 0),
                value(form, "resultFile", ""),
                booleanValue(form, "operationalConfirmation", false)
        );
    }

    public Path reportsRoot() {
        return ticketProjectPath.resolve("load-tests").resolve("gatling")
                .resolve("build").resolve("reports").resolve("gatling");
    }

    public int estimatedVirtualUsers() {
        return estimateVirtualUsers(users, durationSeconds, injectionMode, usersPerSecond, targetUsersPerSecond);
    }

    public boolean generatesAccessTokensFile() {
        return simulationType.usesAccessTokens()
                && "tokens".equals(accessTokenMode)
                && "generate-file".equals(accessTokenSource);
    }

    public boolean usesAccessTokensFile() {
        return simulationType.usesAccessTokens()
                && "tokens".equals(accessTokenMode)
                && (accessTokenSource.equals("generate-file") || accessTokenSource.equals("file"));
    }

    public boolean usesInlineAccessTokens() {
        return simulationType.usesAccessTokens()
                && "tokens".equals(accessTokenMode)
                && accessTokenSource.equals("inline");
    }

    public boolean distributedExecution() {
        return "distributed".equals(executionMode);
    }

    public List<String> distributedHostList() {
        return java.util.Arrays.stream(distributedHosts.split("[,\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(LoadTestRequest::normalizeDistributedHost)
                .toList();
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

    private static boolean booleanValue(
            final Map<String, List<String>> form,
            final String key,
            final boolean defaultValue
    ) {
        final String raw = value(form, key, String.valueOf(defaultValue)).toLowerCase(java.util.Locale.ROOT);
        return raw.equals("true") || raw.equals("on") || raw.equals("1") || raw.equals("yes");
    }

    private static int estimateVirtualUsers(
            final int users,
            final int durationSeconds,
            final String injectionMode,
            final double usersPerSecond,
            final double targetUsersPerSecond
    ) {
        return switch (injectionMode) {
            case "constant-users-per-sec" -> (int) Math.ceil(usersPerSecond * durationSeconds);
            case "ramp-users-per-sec" -> (int) Math.ceil(((usersPerSecond + targetUsersPerSecond) / 2.0) * durationSeconds);
            default -> users;
        };
    }

    private static String defaultIfBlank(final String value, final String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static String defaultAccessTokenSource(final String accessTokens, final String accessTokensFile) {
        if (accessTokensFile != null && !accessTokensFile.isBlank()) {
            return "file";
        }
        if (accessTokens != null && !accessTokens.isBlank()) {
            return "inline";
        }
        return "generate-file";
    }

    private static String defaultAccessTokensFile(final Path ticketProjectPath) {
        final Path workspacePath = Optional.ofNullable(ticketProjectPath.getParent()).orElse(ticketProjectPath);
        return workspacePath.resolve(".tmp").resolve("access-tokens.txt").toString();
    }

    private static String defaultSshKeyPath() {
        final Path userHome = userHomePath();
        final List<Path> candidates = List.of(
                userHome.resolve("OneDrive").resolve("바탕 화면").resolve("ticket").resolve(SSH_KEY_FILE_NAME),
                userHome.resolve("Desktop").resolve("ticket").resolve(SSH_KEY_FILE_NAME)
        );
        return candidates.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(candidates.getLast())
                .toString();
    }

    private static Path userHomePath() {
        final String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null && !userProfile.isBlank()) {
            return Path.of(userProfile);
        }
        return Path.of(System.getProperty("user.home", ""));
    }

    private static String defaultDistributedHosts() {
        return String.join(System.lineSeparator(),
                "ubuntu@43.203.155.15",
                "ubuntu@15.165.40.25",
                "ubuntu@43.203.136.184"
        );
    }

    private static String normalizeDistributedHost(final String value) {
        if (value.contains("@")) {
            return value;
        }
        return "ubuntu@" + value;
    }

    private static String normalizeExecutionMode(final String value) {
        final String mode = defaultIfBlank(value, "local").toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("local") || mode.equals("distributed")) {
            return mode;
        }
        throw new IllegalArgumentException("Unsupported executionMode: " + mode);
    }

    private static String normalizeAccessTokenMode(final String value) {
        final String mode = defaultIfBlank(value, "login").toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("login") || mode.equals("tokens") || mode.equals("synthetic-jwt")) {
            return mode;
        }
        throw new IllegalArgumentException("Unsupported accessTokenMode: " + mode);
    }

    private static String normalizeAccessTokenSource(final String value) {
        final String source = defaultIfBlank(value, "generate-file").toLowerCase(java.util.Locale.ROOT);
        if (source.equals("generate-file") || source.equals("file") || source.equals("inline")) {
            return source;
        }
        throw new IllegalArgumentException("Unsupported accessTokenSource: " + source);
    }

    private static String normalizeAdmissionTokenMode(final String value) {
        final String mode = defaultIfBlank(value, "synthetic").toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("synthetic") || mode.equals("tokens")) {
            return mode;
        }
        throw new IllegalArgumentException("Unsupported admissionTokenMode: " + mode);
    }
}
