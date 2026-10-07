package com.ticket.loadtest;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ClosedInjectionStep;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.Session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;

public final class LoadTestConfig {
    private static final int TICKET_OPEN_PEAK_SECONDS = 10;
    private static final int TICKET_OPEN_FIRST_DECAY_SECONDS = 20;
    private static final int TICKET_OPEN_SECOND_DECAY_SECONDS = 60;
    private static final int TICKET_OPEN_TAIL_SECONDS = 180;
    private static final int TICKET_OPEN_RECOVERY_DELAY_SECONDS = 30;
    private static final int TICKET_OPEN_RECOVERY_SECONDS = 60;
    private static final double TICKET_OPEN_RECOVERY_USERS_PER_SECOND = 1.0;
    private static final int CORE_SPIKE_BASELINE_SECONDS = 30;
    private static final int CORE_SPIKE_RAMP_SECONDS = 5;
    private static final int CORE_SPIKE_RECOVERY_SECONDS = 30;
    private static final int CORE_ACTIVE_USERS_RAMP_SECONDS = 30;
    private static final ConcurrentMap<ConfigKey, CsvValues> CSV_VALUES = new ConcurrentHashMap<>();
    private static final AtomicInteger TOKEN_COUNTER = new AtomicInteger();
    private static final AtomicInteger FAILURE_BODY_DUMP_COUNTER = new AtomicInteger();
    private static final AtomicLong SYNTHETIC_MEMBER_COUNTER =
            new AtomicLong(longProperty(ConfigKey.SYNTHETIC_MEMBER_START_ID));

    private LoadTestConfig() {
    }

    public static String baseUrl() {
        return property(ConfigKey.BASE_URL);
    }

    public static String coreBaseUrl() {
        return propertyOrFallback(ConfigKey.CORE_BASE_URL, ConfigKey.BASE_URL);
    }

    public static String queueBaseUrl() {
        return propertyOrFallback(ConfigKey.QUEUE_BASE_URL, ConfigKey.BASE_URL);
    }

    public static String performanceId() {
        return property(ConfigKey.PERFORMANCE_ID);
    }

    /** 좌석 배치도 조회(/api/v1/shows/{showId}/...)에 쓴다. 기본값은 부하 전용 공연(910000001)이다. */
    public static String showId() {
        return property(ConfigKey.SHOW_ID);
    }

    /** Core 좌석 알림 WebSocket 주소. coreBaseUrl의 http(s)를 ws(s)로 바꾼다. */
    public static String coreWsBaseUrl() {
        final String base = coreBaseUrl();
        if (base.startsWith("https://")) {
            return "wss://" + base.substring("https://".length());
        }
        if (base.startsWith("http://")) {
            return "ws://" + base.substring("http://".length());
        }
        throw new IllegalStateException("coreBaseUrl must start with http:// or https://: " + base);
    }

    /**
     * WebSocket 연결에 보낼 Origin 헤더. 비우면 보내지 않는다 — Core는 Origin이 없는 연결을 브라우저가 아닌 클라이언트로 보고 받는다.
     * Core의 CORS 허용 목록(app.cors.allowed-origins)을 거치게 하려면 그 목록의 값을 준다.
     */
    public static String wsOrigin() {
        return property(ConfigKey.WS_ORIGIN);
    }

    public static int statusPolls() {
        return intProperty(ConfigKey.STATUS_POLLS);
    }

    public static int statusPollPauseSeconds() {
        return intProperty(ConfigKey.STATUS_POLL_PAUSE_SECONDS);
    }

    public static Duration statusPollPauseMin() {
        final int pauseSeconds = nonNegativeIntProperty(ConfigKey.STATUS_POLL_PAUSE_SECONDS);
        final int jitterSeconds = nonNegativeIntProperty(ConfigKey.STATUS_POLL_PAUSE_JITTER_SECONDS);
        return Duration.ofSeconds(Math.max(0, pauseSeconds - jitterSeconds));
    }

    public static Duration statusPollPauseMax() {
        final int pauseSeconds = nonNegativeIntProperty(ConfigKey.STATUS_POLL_PAUSE_SECONDS);
        final int jitterSeconds = nonNegativeIntProperty(ConfigKey.STATUS_POLL_PAUSE_JITTER_SECONDS);
        return Duration.ofSeconds(pauseSeconds + jitterSeconds);
    }

    public static int users() {
        return intProperty(ConfigKey.USERS);
    }

    public static String bookingFeederFile() {
        return property(ConfigKey.BOOKING_FEEDER_FILE);
    }

    public static int bookingFeederOffset() {
        return nonNegativeIntProperty(ConfigKey.BOOKING_FEEDER_OFFSET);
    }

    public static int bookingFeederRows() {
        final int rows = intProperty(ConfigKey.BOOKING_FEEDER_ROWS);
        if (rows < users()) {
            throw new IllegalArgumentException("bookingFeederRows must be at least users for a closed model");
        }
        return rows;
    }

    public static String bookingScenario() {
        return property(ConfigKey.BOOKING_SCENARIO);
    }

    public static int nodeIndex() {
        return nonNegativeIntProperty(ConfigKey.NODE_INDEX);
    }

    public static String resultFile() {
        return property(ConfigKey.RESULT_FILE);
    }

    public static String consoleRunId() {
        return optionalSystemProperty("consoleRunId", "manual");
    }

    public static int durationSeconds() {
        return intProperty(ConfigKey.DURATION_SECONDS);
    }

    public static String injectionMode() {
        return property(ConfigKey.INJECTION_MODE);
    }

    public static double usersPerSecond() {
        return doubleProperty(ConfigKey.USERS_PER_SECOND);
    }

    public static double targetUsersPerSecond() {
        return doubleProperty(ConfigKey.TARGET_USERS_PER_SECOND);
    }

    public static String accessTokenMode() {
        return property(ConfigKey.ACCESS_TOKEN_MODE);
    }

    public static int pollingTimeoutSeconds() {
        return nonNegativeIntProperty(ConfigKey.POLLING_TIMEOUT_SECONDS);
    }

    public static Iterator<Map<String, Object>> bookingFeeder() {
        return bookingFeeder(users());
    }

    public static Iterator<Map<String, Object>> bookingFeeder(final int expectedRows) {
        return BookingFeeder.load(
                Path.of(bookingFeederFile()),
                bookingScenario(),
                expectedRows,
                bookingFeederOffset(),
                Long.parseLong(performanceId())
        );
    }

    public static Duration bookingSeatThinkMin() {
        return durationMin(ConfigKey.BOOKING_SEAT_THINK_MIN_MILLIS, ConfigKey.BOOKING_SEAT_THINK_MAX_MILLIS);
    }

    public static Duration bookingSeatThinkMax() {
        return durationMax(ConfigKey.BOOKING_SEAT_THINK_MIN_MILLIS, ConfigKey.BOOKING_SEAT_THINK_MAX_MILLIS);
    }

    public static Duration bookingOrderThinkMin() {
        return durationMin(ConfigKey.BOOKING_ORDER_THINK_MIN_MILLIS, ConfigKey.BOOKING_ORDER_THINK_MAX_MILLIS);
    }

    public static Duration bookingOrderThinkMax() {
        return durationMax(ConfigKey.BOOKING_ORDER_THINK_MIN_MILLIS, ConfigKey.BOOKING_ORDER_THINK_MAX_MILLIS);
    }

    public static Duration bookingRetryThinkMin() {
        return durationMin(ConfigKey.BOOKING_RETRY_THINK_MIN_MILLIS, ConfigKey.BOOKING_RETRY_THINK_MAX_MILLIS);
    }

    public static Duration bookingRetryThinkMax() {
        return durationMax(ConfigKey.BOOKING_RETRY_THINK_MIN_MILLIS, ConfigKey.BOOKING_RETRY_THINK_MAX_MILLIS);
    }

    public static double bookingSeatRefreshPercent() {
        return percentageProperty(ConfigKey.BOOKING_SEAT_REFRESH_PERCENT);
    }

    public static double bookingDropoutPercent() {
        return percentageProperty(ConfigKey.BOOKING_DROPOUT_PERCENT);
    }

    /** 주문을 만든 뒤 결제 전에 취소하는 사용자 비율. 기본값 10%는 실측이 아니라 가정이다. */
    public static double bookingOrderCancelPercent() {
        return percentageProperty(ConfigKey.BOOKING_ORDER_CANCEL_PERCENT);
    }

    public static double technicalFailureThresholdPercent() {
        final double value = doubleProperty(ConfigKey.TECHNICAL_FAILURE_THRESHOLD_PERCENT);
        if (!Double.isFinite(value) || value <= 0.0 || value > 100.0) {
            throw new IllegalArgumentException("System property must be greater than 0 and at most 100: -D"
                    + ConfigKey.TECHNICAL_FAILURE_THRESHOLD_PERCENT.propertyName());
        }
        return value;
    }

    public static double queueTimeoutThresholdPercent() {
        return nonNegativeDoubleProperty(ConfigKey.QUEUE_TIMEOUT_THRESHOLD_PERCENT);
    }

    public static int maxCoreAdmissionsPerSecond() {
        return nonNegativeIntProperty(ConfigKey.MAX_CORE_ADMISSIONS_PER_SECOND);
    }

    public static double admissionRateTolerancePercent() {
        return nonNegativeDoubleProperty(ConfigKey.ADMISSION_RATE_TOLERANCE_PERCENT);
    }

    public static boolean http2Enabled() {
        return booleanProperty(ConfigKey.HTTP2_ENABLED);
    }

    public static OpenInjectionStep[] injection() {
        final int users = intProperty(ConfigKey.USERS);
        final int durationSeconds = intProperty(ConfigKey.DURATION_SECONDS);
        final String mode = property(ConfigKey.INJECTION_MODE).toLowerCase(Locale.ROOT);
        final List<OpenInjectionStep> steps = new ArrayList<>();
        addScheduledStartDelay(steps);
        switch (mode) {
            case "at-once-users" -> steps.add(atOnceUsers(users));
            case "constant-users-per-sec" -> steps.add(constantUsersPerSec(doubleProperty(ConfigKey.USERS_PER_SECOND))
                    .during(Duration.ofSeconds(durationSeconds)));
            case "ramp-users-per-sec" -> steps.add(rampUsersPerSec(doubleProperty(ConfigKey.USERS_PER_SECOND))
                    .to(doubleProperty(ConfigKey.TARGET_USERS_PER_SECOND))
                    .during(Duration.ofSeconds(durationSeconds)));
            case "spike" -> addCoreSpikeSteps(steps);
            case "ticket-open" -> addTicketOpenSteps(steps, doubleProperty(ConfigKey.USERS_PER_SECOND));
            default -> steps.add(rampUsers(users).during(Duration.ofSeconds(durationSeconds)));
        }
        return steps.toArray(OpenInjectionStep[]::new);
    }

    public static OpenInjectionStep[] coreAdmissionCapacityInjection() {
        if (!"constant-users-per-sec".equalsIgnoreCase(injectionMode())) {
            throw new IllegalArgumentException(
                    "CoreAdmissionCapacitySimulation requires -DinjectionMode=constant-users-per-sec"
            );
        }
        return injection();
    }

    public static OpenInjectionStep[] coreSpikeInjection() {
        final List<OpenInjectionStep> steps = new ArrayList<>();
        addScheduledStartDelay(steps);
        addCoreSpikeSteps(steps);
        return steps.toArray(OpenInjectionStep[]::new);
    }

    public static ClosedInjectionStep[] coreActiveUsersInjection() {
        return new ClosedInjectionStep[]{
                rampConcurrentUsers(0).to(users())
                        .during(Duration.ofSeconds(CORE_ACTIVE_USERS_RAMP_SECONDS)),
                constantConcurrentUsers(users())
                        .during(Duration.ofSeconds(intProperty(ConfigKey.DURATION_SECONDS)))
        };
    }

    public static OpenInjectionStep[] ticketOpenRecoveryInjection() {
        if (!ticketOpenEnabled()) {
            throw new IllegalStateException("Ticket-open recovery injection requires -DinjectionMode=ticket-open");
        }
        final List<OpenInjectionStep> steps = new ArrayList<>();
        addScheduledStartDelay(steps);
        steps.add(nothingFor(ticketOpenDuration().plusSeconds(TICKET_OPEN_RECOVERY_DELAY_SECONDS)));
        steps.add(constantUsersPerSec(TICKET_OPEN_RECOVERY_USERS_PER_SECOND)
                .during(Duration.ofSeconds(TICKET_OPEN_RECOVERY_SECONDS)));
        return steps.toArray(OpenInjectionStep[]::new);
    }

    public static boolean ticketOpenEnabled() {
        return "ticket-open".equalsIgnoreCase(property(ConfigKey.INJECTION_MODE));
    }

    public static int expectedUsers() {
        final int users = intProperty(ConfigKey.USERS);
        final int durationSeconds = intProperty(ConfigKey.DURATION_SECONDS);
        final double usersPerSecond = doubleProperty(ConfigKey.USERS_PER_SECOND);
        return switch (property(ConfigKey.INJECTION_MODE).toLowerCase(Locale.ROOT)) {
            case "at-once-users" -> users;
            case "constant-users-per-sec" -> estimateConstantUsers(usersPerSecond, durationSeconds);
            case "ramp-users-per-sec" -> estimateRampUsers(usersPerSecond,
                    doubleProperty(ConfigKey.TARGET_USERS_PER_SECOND), durationSeconds);
            case "spike" -> coreSpikeExpectedUsers();
            case "ticket-open" -> ticketOpenExpectedUsers(usersPerSecond);
            default -> users;
        };
    }

    public static int coreSpikeExpectedUsers() {
        final double baselineUsersPerSecond = doubleProperty(ConfigKey.USERS_PER_SECOND);
        final double peakUsersPerSecond = doubleProperty(ConfigKey.TARGET_USERS_PER_SECOND);
        final int peakHoldSeconds = intProperty(ConfigKey.DURATION_SECONDS);
        return estimateConstantUsers(baselineUsersPerSecond, CORE_SPIKE_BASELINE_SECONDS)
                + estimateRampUsers(baselineUsersPerSecond, peakUsersPerSecond, CORE_SPIKE_RAMP_SECONDS)
                + estimateConstantUsers(peakUsersPerSecond, peakHoldSeconds)
                + estimateRampUsers(peakUsersPerSecond, baselineUsersPerSecond, CORE_SPIKE_RAMP_SECONDS)
                + estimateConstantUsers(baselineUsersPerSecond, CORE_SPIKE_RECOVERY_SECONDS);
    }

    public static ChainBuilder initializeSession() {
        return exec(session -> session.set("performanceId", performanceId()));
    }

    public static ChainBuilder authenticate() {
        final String mode = property(ConfigKey.ACCESS_TOKEN_MODE).toLowerCase(Locale.ROOT);
        if ("tokens".equals(mode)) {
            return exec(session -> {
                final String accessToken = nextAccessToken();
                return session.set("accessToken", accessToken)
                        .set("memberId", LoadTestTokens.readSubjectAsLong(accessToken));
            });
        }
        // Core는 이메일·비밀번호 로그인이 없다(소셜 전용). 로그인 API 대신 JWT_SECRET으로 서명한 합성 access token을 쓴다.
        if ("synthetic-jwt".equals(mode)) {
            return exec(session -> {
                final long memberId = SYNTHETIC_MEMBER_COUNTER.getAndIncrement();
                return session.set("memberId", memberId)
                        .set("accessToken", createSyntheticJwt(memberId));
            });
        }
        throw new IllegalArgumentException("Unsupported accessTokenMode: " + mode + " (synthetic-jwt 또는 tokens)");
    }

    public static Map<CharSequence, String> authHeaders() {
        return Map.of("Authorization", "Bearer #{accessToken}");
    }
    public static Map<CharSequence, String> loadTestCorrelationHeaders() {
        return Map.of(
                "X-Load-Test-Run-Id", consoleRunId(),
                "X-Load-Test-Scenario", bookingScenario()
        );
    }

    public static Map<CharSequence, String> authAndCorrelationHeaders() {
        return Map.of(
                "Authorization", "Bearer #{accessToken}",
                "X-Load-Test-Run-Id", consoleRunId(),
                "X-Load-Test-Scenario", bookingScenario(),
                "X-Load-Test-User-Id", "#{memberId}"
        );
    }

    public static Map<CharSequence, String> bookingCorrelationHeaders() {
        return Map.of(
                "X-Load-Test-Run-Id", consoleRunId(),
                "X-Load-Test-Scenario", bookingScenario(),
                "X-Load-Test-User-Id", "#{memberId}"
        );
    }


    public static boolean dumpFailureBodyEnabled() {
        return booleanProperty(ConfigKey.DUMP_FAILURE_BODY);
    }

    public static ChainBuilder dumpFailureResponseBody(final String requestName) {
        return exec(session -> {
            if (!dumpFailureBodyEnabled() || !session.contains("httpStatus")) {
                return session;
            }

            final int status = session.getInt("httpStatus");
            if (status == 200) {
                return removeFailureResponseDebugValues(session);
            }

            final int dumpIndex = FAILURE_BODY_DUMP_COUNTER.incrementAndGet();
            if (dumpIndex > intProperty(ConfigKey.DUMP_FAILURE_BODY_LIMIT)) {
                return removeFailureResponseDebugValues(session);
            }

            final String responseBody = session.contains("responseBody") ? session.getString("responseBody") : "";
            final Path outputDir = Path.of(property(ConfigKey.FAILURE_BODY_DIR));
            final String fileBaseName = sanitizeFileName(requestName) + "-" + dumpIndex + "-status-" + status;
            final Path bodyPath = outputDir.resolve(fileBaseName + ".html");
            final Path metaPath = outputDir.resolve(fileBaseName + ".txt");

            try {
                Files.createDirectories(outputDir);
                Files.writeString(bodyPath, responseBody, StandardCharsets.UTF_8);
                Files.writeString(metaPath, failureBodyMetadata(requestName, status, bodyPath, session), StandardCharsets.UTF_8);
                System.out.println("Failure response body dumped: " + bodyPath.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("Failed to dump failure response body: " + e.getMessage());
            }

            return removeFailureResponseDebugValues(session);
        });
    }

    public static Map<CharSequence, String> queueTokenHeaders() {
        return Map.of("X-Queue-Token", "#{queueToken}");
    }

    public static Map<CharSequence, String> authAndAdmissionHeaders() {
        return Map.of(
                "Authorization", "Bearer #{accessToken}",
                "X-Admission-Token", "#{admissionToken}"
        );
    }

    private static String property(final ConfigKey key) {
        final String value = System.getProperty(key.propertyName());
        if (value == null || value.isBlank()) {
            final String defaultValue = key.defaultValue();
            if (defaultValue == null) {
                throw new IllegalStateException("Missing required system property: -D" + key.propertyName());
            }
            return defaultValue;
        }
        return value.trim();
    }

    private static String optionalProperty(final ConfigKey key, final String defaultValue) {
        final String value = System.getProperty(key.propertyName());
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static String propertyOrFallback(final ConfigKey key, final ConfigKey fallbackKey) {
        final String value = System.getProperty(key.propertyName());
        return value == null || value.isBlank() ? property(fallbackKey) : value.trim();
    }

    private static String optionalSystemProperty(final String propertyName, final String defaultValue) {
        final String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }


    private static int intProperty(final ConfigKey key) {
        return Integer.parseInt(property(key));
    }

    private static boolean booleanProperty(final ConfigKey key) {
        return Boolean.parseBoolean(property(key));
    }

    private static int nonNegativeIntProperty(final ConfigKey key) {
        final int value = intProperty(key);
        if (value < 0) {
            throw new IllegalArgumentException("System property must be non-negative: -D" + key.propertyName());
        }
        return value;
    }

    private static long longProperty(final ConfigKey key) {
        return Long.parseLong(property(key));
    }

    private static double doubleProperty(final ConfigKey key) {
        return Double.parseDouble(property(key));
    }

    private static double nonNegativeDoubleProperty(final ConfigKey key) {
        final double value = doubleProperty(key);
        if (value < 0) {
            throw new IllegalArgumentException("System property must be non-negative: -D" + key.propertyName());
        }
        return value;
    }

    private static Duration durationMin(final ConfigKey minKey, final ConfigKey maxKey) {
        final int minMillis = nonNegativeIntProperty(minKey);
        final int maxMillis = nonNegativeIntProperty(maxKey);
        validateDurationRange(minKey, maxKey, minMillis, maxMillis);
        return Duration.ofMillis(minMillis);
    }

    private static Duration durationMax(final ConfigKey minKey, final ConfigKey maxKey) {
        final int minMillis = nonNegativeIntProperty(minKey);
        final int maxMillis = nonNegativeIntProperty(maxKey);
        validateDurationRange(minKey, maxKey, minMillis, maxMillis);
        return Duration.ofMillis(maxMillis);
    }

    private static void validateDurationRange(
            final ConfigKey minKey,
            final ConfigKey maxKey,
            final int minMillis,
            final int maxMillis
    ) {
        if (minMillis > maxMillis) {
            throw new IllegalArgumentException("System property range is invalid: -D"
                    + minKey.propertyName() + " must not exceed -D" + maxKey.propertyName());
        }
    }

    private static double percentageProperty(final ConfigKey key) {
        final double value = nonNegativeDoubleProperty(key);
        if (value > 100.0) {
            throw new IllegalArgumentException("System property must be at most 100: -D" + key.propertyName());
        }
        return value;
    }

    private static String nextAccessToken() {
        final CsvValues csvValues = CSV_VALUES.computeIfAbsent(ConfigKey.ACCESS_TOKENS, ignored -> parseAccessTokens());
        final int index = TOKEN_COUNTER.getAndIncrement();
        if (index >= csvValues.values().size()) {
            throw new IllegalStateException("Access token values exhausted");
        }
        return csvValues.values().get(index);
    }

    private static CsvValues parseAccessTokens() {
        final List<String> values = LoadTestTokenValues.fromCsvOrFile(
                System.getProperty(ConfigKey.ACCESS_TOKENS.propertyName()),
                optionalProperty(ConfigKey.ACCESS_TOKENS_FILE, ""),
                ConfigKey.ACCESS_TOKENS.propertyName()
        );
        if (values.size() < expectedUsers()) {
            throw new IllegalStateException("Access token values are fewer than expected users: required="
                    + expectedUsers() + ", available=" + values.size());
        }
        final Set<Long> memberIds = new HashSet<>();
        for (String value : values) {
            if (!memberIds.add(LoadTestTokens.readSubjectAsLong(value))) {
                throw new IllegalStateException("Access token values must have unique member subjects");
            }
        }
        return new CsvValues(values);
    }

    private static void addScheduledStartDelay(final List<OpenInjectionStep> steps) {
        final long startAtEpochMillis = longProperty(ConfigKey.START_AT_EPOCH_MILLIS);
        if (startAtEpochMillis <= 0L) {
            return;
        }
        final long delayMillis = startAtEpochMillis - System.currentTimeMillis();
        if (delayMillis <= 0L) {
            throw new IllegalStateException("Scheduled load-test start time has already passed");
        }
        steps.add(nothingFor(Duration.ofMillis(delayMillis)));
    }

    private static void addTicketOpenSteps(final List<OpenInjectionStep> steps, final double peakUsersPerSecond) {
        final double firstDecayUsersPerSecond = peakUsersPerSecond * 0.5;
        final double secondDecayUsersPerSecond = peakUsersPerSecond * 0.2;
        final double tailUsersPerSecond = peakUsersPerSecond * 0.1;
        steps.add(constantUsersPerSec(peakUsersPerSecond)
                .during(Duration.ofSeconds(TICKET_OPEN_PEAK_SECONDS)));
        steps.add(rampUsersPerSec(peakUsersPerSecond).to(firstDecayUsersPerSecond)
                .during(Duration.ofSeconds(TICKET_OPEN_FIRST_DECAY_SECONDS)));
        steps.add(rampUsersPerSec(firstDecayUsersPerSecond).to(secondDecayUsersPerSecond)
                .during(Duration.ofSeconds(TICKET_OPEN_SECOND_DECAY_SECONDS)));
        steps.add(rampUsersPerSec(secondDecayUsersPerSecond).to(tailUsersPerSecond)
                .during(Duration.ofSeconds(TICKET_OPEN_TAIL_SECONDS)));
    }

    private static void addCoreSpikeSteps(final List<OpenInjectionStep> steps) {
        final double baselineUsersPerSecond = doubleProperty(ConfigKey.USERS_PER_SECOND);
        final double peakUsersPerSecond = doubleProperty(ConfigKey.TARGET_USERS_PER_SECOND);
        if (peakUsersPerSecond <= baselineUsersPerSecond) {
            throw new IllegalArgumentException("-DtargetUsersPerSecond must be greater than -DusersPerSecond");
        }
        steps.add(constantUsersPerSec(baselineUsersPerSecond)
                .during(Duration.ofSeconds(CORE_SPIKE_BASELINE_SECONDS)));
        steps.add(rampUsersPerSec(baselineUsersPerSecond).to(peakUsersPerSecond)
                .during(Duration.ofSeconds(CORE_SPIKE_RAMP_SECONDS)));
        steps.add(constantUsersPerSec(peakUsersPerSecond)
                .during(Duration.ofSeconds(intProperty(ConfigKey.DURATION_SECONDS))));
        steps.add(rampUsersPerSec(peakUsersPerSecond).to(baselineUsersPerSecond)
                .during(Duration.ofSeconds(CORE_SPIKE_RAMP_SECONDS)));
        steps.add(constantUsersPerSec(baselineUsersPerSecond)
                .during(Duration.ofSeconds(CORE_SPIKE_RECOVERY_SECONDS)));
    }

    private static int ticketOpenExpectedUsers(final double peakUsersPerSecond) {
        final double firstDecayUsersPerSecond = peakUsersPerSecond * 0.5;
        final double secondDecayUsersPerSecond = peakUsersPerSecond * 0.2;
        final double tailUsersPerSecond = peakUsersPerSecond * 0.1;
        return estimateConstantUsers(peakUsersPerSecond, TICKET_OPEN_PEAK_SECONDS)
                + estimateRampUsers(peakUsersPerSecond, firstDecayUsersPerSecond, TICKET_OPEN_FIRST_DECAY_SECONDS)
                + estimateRampUsers(firstDecayUsersPerSecond, secondDecayUsersPerSecond, TICKET_OPEN_SECOND_DECAY_SECONDS)
                + estimateRampUsers(secondDecayUsersPerSecond, tailUsersPerSecond, TICKET_OPEN_TAIL_SECONDS)
                + estimateConstantUsers(TICKET_OPEN_RECOVERY_USERS_PER_SECOND, TICKET_OPEN_RECOVERY_SECONDS);
    }

    private static int estimateConstantUsers(final double usersPerSecond, final int durationSeconds) {
        return (int) Math.ceil(usersPerSecond * durationSeconds);
    }

    private static int estimateRampUsers(
            final double startUsersPerSecond,
            final double endUsersPerSecond,
            final int durationSeconds
    ) {
        return (int) Math.ceil(((startUsersPerSecond + endUsersPerSecond) / 2.0) * durationSeconds);
    }

    private static Duration ticketOpenDuration() {
        return Duration.ofSeconds(TICKET_OPEN_PEAK_SECONDS
                + TICKET_OPEN_FIRST_DECAY_SECONDS
                + TICKET_OPEN_SECOND_DECAY_SECONDS
                + TICKET_OPEN_TAIL_SECONDS);
    }

    public static String syntheticAccessTokenForMember(final long memberId) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        return createSyntheticJwt(memberId);
    }

    private static String createSyntheticJwt(final Long memberId) {
        return LoadTestTokens.createAccessToken(
                property(ConfigKey.JWT_ISSUER),
                property(ConfigKey.JWT_SECRET),
                memberId,
                property(ConfigKey.SYNTHETIC_JWT_ROLE),
                Instant.now(),
                intProperty(ConfigKey.SYNTHETIC_TOKEN_TTL_SECONDS)
        );
    }

    private static String failureBodyMetadata(
            final String requestName,
            final int status,
            final Path bodyPath,
            final Session session
    ) {
        return """
                request=%s
                status=%d
                server=%s
                cfRay=%s
                cfCacheStatus=%s
                baseUrl=%s
                queueBaseUrl=%s
                bodyPath=%s
                """.formatted(
                requestName,
                status,
                optionalSessionValue(session, "responseServer"),
                optionalSessionValue(session, "responseCfRay"),
                optionalSessionValue(session, "responseCfCacheStatus"),
                baseUrl(),
                queueBaseUrl(),
                bodyPath.toAbsolutePath()
        );
    }

    private static String optionalSessionValue(final Session session, final String key) {
        return session.contains(key) ? session.getString(key) : "";
    }

    private static Session removeFailureResponseDebugValues(final Session session) {
        return session.removeAll(
                "httpStatus",
                "responseBody",
                "responseServer",
                "responseCfRay",
                "responseCfCacheStatus"
        );
    }

    private static String sanitizeFileName(final String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private record CsvValues(List<String> values) {
    }

    private enum ConfigKey {
        BASE_URL("baseUrl", null),
        CORE_BASE_URL("coreBaseUrl", null),
        QUEUE_BASE_URL("queueBaseUrl", null),
        PERFORMANCE_ID("performanceId", "1"),
        STATUS_POLLS("statusPolls", "3"),
        STATUS_POLL_PAUSE_SECONDS("statusPollPauseSeconds", "1"),
        STATUS_POLL_PAUSE_JITTER_SECONDS("statusPollPauseJitterSeconds", "0"),
        HTTP2_ENABLED("http2Enabled", "false"),
        USERS("users", "10"),
        DURATION_SECONDS("durationSeconds", "10"),
        INJECTION_MODE("injectionMode", "ramp-users"),
        USERS_PER_SECOND("usersPerSecond", "1.0"),
        TARGET_USERS_PER_SECOND("targetUsersPerSecond", "10.0"),
        ACCESS_TOKEN_MODE("accessTokenMode", "synthetic-jwt"),
        ACCESS_TOKENS("accessTokens", null),
        ACCESS_TOKENS_FILE("accessTokensFile", null),
        SYNTHETIC_MEMBER_START_ID("syntheticMemberStartId", "1"),
        SYNTHETIC_TOKEN_TTL_SECONDS("syntheticTokenTtlSeconds", "3600"),
        JWT_ISSUER("jwtIssuer", "ticket"),
        SYNTHETIC_JWT_ROLE("syntheticJwtRole", "MEMBER"),
        JWT_SECRET("jwtSecret", null),
        DUMP_FAILURE_BODY("dumpFailureBody", "false"),
        DUMP_FAILURE_BODY_LIMIT("dumpFailureBodyLimit", "1"),
        FAILURE_BODY_DIR("failureBodyDir", "../../distributed-results-join/_latest/failure-bodies"),
        BOOKING_FEEDER_FILE("bookingFeederFile", "build/booking-feeder.csv"),
        BOOKING_FEEDER_OFFSET("bookingFeederOffset", "0"),
        BOOKING_FEEDER_ROWS("bookingFeederRows", null),
        BOOKING_SCENARIO("bookingScenario", "TICKET_OPEN_END_TO_END"),
        NODE_INDEX("nodeIndex", "0"),
        RESULT_FILE("resultFile", "../../distributed-results-join/_latest/booking-results.csv"),
        POLLING_TIMEOUT_SECONDS("pollingTimeoutSeconds", "300"),
        BOOKING_SEAT_THINK_MIN_MILLIS("bookingSeatThinkMinMillis", "1000"),
        BOOKING_SEAT_THINK_MAX_MILLIS("bookingSeatThinkMaxMillis", "3000"),
        BOOKING_ORDER_THINK_MIN_MILLIS("bookingOrderThinkMinMillis", "2000"),
        BOOKING_ORDER_THINK_MAX_MILLIS("bookingOrderThinkMaxMillis", "6000"),
        BOOKING_RETRY_THINK_MIN_MILLIS("bookingRetryThinkMinMillis", "500"),
        BOOKING_RETRY_THINK_MAX_MILLIS("bookingRetryThinkMaxMillis", "2000"),
        BOOKING_SEAT_REFRESH_PERCENT("bookingSeatRefreshPercent", "25.0"),
        BOOKING_DROPOUT_PERCENT("bookingDropoutPercent", "10.0"),
        BOOKING_ORDER_CANCEL_PERCENT("bookingOrderCancelPercent", "10.0"),
        SHOW_ID("showId", "910000001"),
        WS_ORIGIN("wsOrigin", ""),
        TECHNICAL_FAILURE_THRESHOLD_PERCENT("technicalFailureThresholdPercent", "1.0"),
        QUEUE_TIMEOUT_THRESHOLD_PERCENT("queueTimeoutThresholdPercent", "0.0"),
        MAX_CORE_ADMISSIONS_PER_SECOND("maxCoreAdmissionsPerSecond", "0"),
        ADMISSION_RATE_TOLERANCE_PERCENT("admissionRateTolerancePercent", "10.0"),
        START_AT_EPOCH_MILLIS("startAtEpochMillis", "0");

        private final String propertyName;
        private final String defaultValue;

        ConfigKey(final String propertyName, final String defaultValue) {
            this.propertyName = propertyName;
            this.defaultValue = defaultValue;
        }

        private String propertyName() {
            return propertyName;
        }

        private String defaultValue() {
            return defaultValue;
        }
    }
}
