package com.ticket.gatling.console;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public record LoadTestRequest(
        Path ticketProjectPath,
        SimulationType simulationType,
        String baseUrl,
        String coreBaseUrl,
        String queueBaseUrl,
        String performanceId,
        int users,
        int durationSeconds,
        String injectionMode,
        double usersPerSecond,
        double targetUsersPerSecond,
        String executionMode,
        boolean http2Enabled,
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
        String jwtSecret,
        String jwtIssuer,
        long syntheticMemberStartId,
        String memberIdsFile,
        String syntheticJwtRole,
        int syntheticTokenTtlSeconds,
        String accessTokens,
        String accessTokenSource,
        String accessTokensFile,
        int generatedAccessTokenCount,
        String bookingFeederFile,
        int bookingFeederOffset,
        int bookingFeederRows,
        String resultFile,
        double technicalFailureThresholdPercent,
        double queueTimeoutThresholdPercent,
        int maxCoreAdmissionsPerSecond,
        double admissionRateTolerancePercent,
        boolean operationalConfirmation
) {
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final String DEFAULT_LOAD_TESTS_PATH =
            "C:\\Users\\mn040\\IdeaProjects\\ticket-workspace\\gatling-test";
    private static final String SSH_KEY_FILE_NAME = "ticket-test-key-01.pem";
    private static final String SYNTHETIC_JWT_SECRET = "0123456789abcdef0123456789abcdef";

    public LoadTestRequest {
        require(users > 0, "users must be positive");
        require(bookingFeederRows > 0, "bookingFeederRows must be positive");
        require(bookingFeederOffset >= 0, "bookingFeederOffset must be non-negative");
        require(durationSeconds > 0, "durationSeconds must be positive");
        require(syntheticMemberStartId > 0, "syntheticMemberStartId must be positive");
        require(syntheticTokenTtlSeconds > 0, "syntheticTokenTtlSeconds must be positive");
        require(statusPollPauseSeconds >= 0, "statusPollPauseSeconds must be non-negative");
        require(statusPollPauseJitterSeconds >= 0, "statusPollPauseJitterSeconds must be non-negative");
        require(pollingTimeoutSeconds >= 0, "pollingTimeoutSeconds must be non-negative");
        require(queueTimeoutThresholdPercent >= 0, "queueTimeoutThresholdPercent must be non-negative");
        require(technicalFailureThresholdPercent > 0.0 && technicalFailureThresholdPercent <= 100.0,
                "technicalFailureThresholdPercent must be greater than 0 and at most 100");
        require(maxCoreAdmissionsPerSecond >= 0, "maxCoreAdmissionsPerSecond must be non-negative");
        require(admissionRateTolerancePercent >= 0, "admissionRateTolerancePercent must be non-negative");
        require(distributedDumpFailureBodyLimit > 0, "distributedDumpFailureBodyLimit must be positive");
    }

    public static LoadTestRequest fromForm(final Map<String, List<String>> form) {
        final Path ticketProjectPath = Path.of(value(form, "ticketProjectPath", DEFAULT_LOAD_TESTS_PATH))
                .toAbsolutePath().normalize();
        final SimulationType simulationType =
                SimulationType.fromKey(value(form, "simulation", SimulationType.QUEUE_ENTER.key()));
        final boolean coreFlow = simulationType.usesCoreBookingFlow();
        final boolean queueProtectsCore = simulationType == SimulationType.QUEUE_PROTECTS_CORE;
        final String baseUrl = value(form, "baseUrl", "");
        final int users = intValue(form, "users", 10);
        final int durationSeconds = intValue(form, "durationSeconds", 10);
        final String injectionMode = value(form, "injectionMode", "ramp-users");
        final double usersPerSecond = doubleValue(form, "usersPerSecond", 1.0);
        final double targetUsersPerSecond = doubleValue(form, "targetUsersPerSecond", 10.0);
        final String accessTokens = value(form, "accessTokens", "");
        final String accessTokensFile = value(form, "accessTokensFile", "");
        String accessTokenSource = oneOf(form, "accessTokenSource",
                defaultAccessTokenSource(accessTokens, accessTokensFile), "generate-file", "file", "inline");
        if ("inline".equals(accessTokenSource) && accessTokens.isBlank()) {
            accessTokenSource = "generate-file";
        }
        final int generatedAccessTokenCount = intValue(form, "generatedAccessTokenCount", 0);
        return new LoadTestRequest(
                ticketProjectPath,
                simulationType,
                baseUrl,
                value(form, "coreBaseUrl", coreFlow ? "" : baseUrl),
                value(form, "queueBaseUrl", coreFlow ? "" : baseUrl),
                value(form, "performanceId", "1"),
                users,
                durationSeconds,
                injectionMode,
                usersPerSecond,
                targetUsersPerSecond,
                oneOf(form, "executionMode", "local", "local", "distributed"),
                simulationType == SimulationType.QUEUE_JOIN_ONLY && booleanValue(form, "http2Enabled", false),
                booleanValue(form, "distributedIncludeLocal", false),
                booleanValue(form, "distributedCollectReports", true),
                booleanValue(form, "distributedDumpFailureBody", false),
                intValue(form, "distributedDumpFailureBodyLimit", 1),
                value(form, "distributedHosts", defaultDistributedHosts()),
                Path.of(value(form, "sshKeyPath", defaultSshKeyPath())).toAbsolutePath().normalize(),
                value(form, "distributedRemoteProjectDir", "~/gatling-test"),
                intValue(form, "statusPolls", 3),
                intValue(form, "statusPollPauseSeconds", 1),
                intValue(form, "statusPollPauseJitterSeconds", 0),
                intValue(form, "pollingTimeoutSeconds", 300),
                // Core에 이메일·비밀번호 로그인이 없으므로(소셜 전용) 로그인 API로 토큰을 받는 모드는 없다.
                oneOf(form, "accessTokenMode", "synthetic-jwt", "tokens", "synthetic-jwt"),
                value(form, "jwtSecret", SYNTHETIC_JWT_SECRET),
                value(form, "jwtIssuer", "ticket"),
                longValue(form, "syntheticMemberStartId", 1L),
                value(form, "memberIdsFile", ""),
                value(form, "syntheticJwtRole", "MEMBER"),
                intValue(form, "syntheticTokenTtlSeconds", 3600),
                accessTokens,
                accessTokenSource,
                accessTokensFile.isBlank() ? defaultAccessTokensFile(ticketProjectPath) : accessTokensFile,
                generatedAccessTokenCount > 0
                        ? generatedAccessTokenCount
                        : estimateVirtualUsers(users, durationSeconds, injectionMode, usersPerSecond, targetUsersPerSecond),
                value(form, "bookingFeederFile", "build/booking-feeder.csv"),
                simulationType == SimulationType.CORE_ADMISSION_CAPACITY ? intValue(form, "bookingFeederOffset", 0) : 0,
                intValue(form, "bookingFeederRows", 10000),
                value(form, "resultFile", "../../distributed-results-join/_latest/booking-results.csv"),
                doubleValue(form, "technicalFailureThresholdPercent", 1.0),
                simulationType.usesQueueBaseUrl() ? doubleValue(form, "queueTimeoutThresholdPercent", 0.0) : 0.0,
                queueProtectsCore ? intValue(form, "maxCoreAdmissionsPerSecond", 0) : 0,
                queueProtectsCore ? doubleValue(form, "admissionRateTolerancePercent", 10.0) : 0.0,
                booleanValue(form, "operationalConfirmation", false)
        );
    }

    public RunEnvironmentInput environment() {
        return RunEnvironmentInput.automatic(simulationType, baseUrl, coreBaseUrl, queueBaseUrl);
    }

    public Path reportsRoot() {
        return ticketProjectPath.resolve("distributed-results-join");
    }

    String gradleWrapper() {
        return ticketProjectPath.resolve(WINDOWS ? "gradlew.bat" : "gradlew").toString();
    }

    public int estimatedVirtualUsers() {
        return estimateVirtualUsers(users, durationSeconds, injectionMode, usersPerSecond, targetUsersPerSecond);
    }

    public boolean closedBookingModel() {
        return simulationType == SimulationType.CORE_ACTIVE_USERS_CLOSED;
    }

    public int expectedBookingRowsPerNode() {
        return closedBookingModel() ? bookingFeederRows : estimatedVirtualUsers();
    }

    public boolean generatesAccessTokensFile() {
        return usesTokenMode() && "generate-file".equals(accessTokenSource);
    }

    public boolean usesAccessTokensFile() {
        return usesTokenMode() && (accessTokenSource.equals("generate-file") || accessTokenSource.equals("file"));
    }

    public boolean usesInlineAccessTokens() {
        return usesTokenMode() && accessTokenSource.equals("inline");
    }

    private boolean usesTokenMode() {
        return simulationType.usesAccessTokens() && "tokens".equals(accessTokenMode);
    }

    public boolean distributedExecution() {
        return "distributed".equals(executionMode);
    }

    public List<String> distributedHostList() {
        return Arrays.stream(distributedHosts.split("[,\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.contains("@") ? value : "ubuntu@" + value)
                .toList();
    }

    /** localhost·loopback 대상인지. 콘솔 서버와 index.html이 같은 규칙을 쓴다. */
    static boolean isLocalUrl(final String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            final String rawHost = new URI(url.trim()).getHost();
            if (rawHost == null) {
                return false;
            }
            final String host = rawHost.toLowerCase(Locale.ROOT).replaceAll("^\\[|]$", "");
            return host.equals("localhost")
                    || host.endsWith(".localhost")
                    || host.startsWith("127.")
                    || host.equals("::1")
                    || host.equals("0:0:0:0:0:0:0:1")
                    || host.equals("0.0.0.0");
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
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

    private static String oneOf(
            final Map<String, List<String>> form,
            final String key,
            final String defaultValue,
            final String... allowed
    ) {
        final String value = value(form, key, defaultValue).toLowerCase(Locale.ROOT);
        require(List.of(allowed).contains(value), "Unsupported " + key + ": " + value);
        return value;
    }

    private static int intValue(final Map<String, List<String>> form, final String key, final int defaultValue) {
        return Integer.parseInt(value(form, key, String.valueOf(defaultValue)));
    }

    private static double doubleValue(final Map<String, List<String>> form, final String key, final double defaultValue) {
        return Double.parseDouble(value(form, key, String.valueOf(defaultValue)));
    }

    private static long longValue(final Map<String, List<String>> form, final String key, final long defaultValue) {
        return Long.parseLong(value(form, key, String.valueOf(defaultValue)));
    }

    private static boolean booleanValue(
            final Map<String, List<String>> form,
            final String key,
            final boolean defaultValue
    ) {
        final String raw = value(form, key, String.valueOf(defaultValue)).toLowerCase(Locale.ROOT);
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
            case "constant-users-per-sec" -> constantUsers(usersPerSecond, durationSeconds);
            case "ramp-users-per-sec" -> rampUsers(usersPerSecond, targetUsersPerSecond, durationSeconds);
            case "ticket-open" -> ticketOpenExpectedUsers(usersPerSecond);
            case "spike" -> coreSpikeExpectedUsers(usersPerSecond, targetUsersPerSecond, durationSeconds);
            default -> users;
        };
    }

    private static int coreSpikeExpectedUsers(
            final double baselineUsersPerSecond,
            final double peakUsersPerSecond,
            final int peakHoldSeconds
    ) {
        return constantUsers(baselineUsersPerSecond, 30)
                + rampUsers(baselineUsersPerSecond, peakUsersPerSecond, 5)
                + constantUsers(peakUsersPerSecond, peakHoldSeconds)
                + rampUsers(peakUsersPerSecond, baselineUsersPerSecond, 5)
                + constantUsers(baselineUsersPerSecond, 30);
    }

    private static int ticketOpenExpectedUsers(final double peakUsersPerSecond) {
        final double firstDecay = peakUsersPerSecond * 0.5;
        final double secondDecay = peakUsersPerSecond * 0.2;
        final double tail = peakUsersPerSecond * 0.1;
        return constantUsers(peakUsersPerSecond, 10)
                + rampUsers(peakUsersPerSecond, firstDecay, 20)
                + rampUsers(firstDecay, secondDecay, 60)
                + rampUsers(secondDecay, tail, 180)
                + constantUsers(1.0, 60);
    }

    private static int constantUsers(final double usersPerSecond, final int durationSeconds) {
        return (int) Math.ceil(usersPerSecond * durationSeconds);
    }

    private static int rampUsers(
            final double startUsersPerSecond,
            final double endUsersPerSecond,
            final int durationSeconds
    ) {
        return (int) Math.ceil(((startUsersPerSecond + endUsersPerSecond) / 2.0) * durationSeconds);
    }

    private static String defaultAccessTokenSource(final String accessTokens, final String accessTokensFile) {
        if (!accessTokensFile.isBlank()) {
            return "file";
        }
        if (!accessTokens.isBlank()) {
            return "inline";
        }
        return "generate-file";
    }

    private static String defaultAccessTokensFile(final Path ticketProjectPath) {
        final Path workspacePath = Optional.ofNullable(ticketProjectPath.getParent()).orElse(ticketProjectPath);
        return workspacePath.resolve(".tmp").resolve("access-tokens.txt").toString();
    }

    private static String defaultSshKeyPath() {
        final String userProfile = System.getenv("USERPROFILE");
        final Path userHome = Path.of(userProfile != null && !userProfile.isBlank()
                ? userProfile
                : System.getProperty("user.home", ""));
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

    private static String defaultDistributedHosts() {
        return String.join(System.lineSeparator(),
                "ubuntu@43.203.155.15",
                "ubuntu@15.165.40.25",
                "ubuntu@43.203.136.184"
        );
    }
}
