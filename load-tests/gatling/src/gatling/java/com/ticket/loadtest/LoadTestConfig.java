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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

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
    private static final AtomicInteger LOGIN_COUNTER = new AtomicInteger(intProperty(ConfigKey.LOGIN_START_INDEX));
    private static final AtomicInteger TOKEN_COUNTER = new AtomicInteger();
    private static final AtomicInteger ADMISSION_TOKEN_COUNTER = new AtomicInteger();
    private static final AtomicInteger SEAT_ID_COUNTER = new AtomicInteger();
    private static final AtomicInteger FAILURE_BODY_DUMP_COUNTER = new AtomicInteger();
    private static final AtomicLong SYNTHETIC_MEMBER_COUNTER =
            new AtomicLong(longProperty(ConfigKey.SYNTHETIC_MEMBER_START_ID));

    private LoadTestConfig() {
    }

    public static String baseUrl() {
        return property(ConfigKey.BASE_URL);
    }

    public static String coreBaseUrl() {
        return optionalProperty(ConfigKey.CORE_BASE_URL, baseUrl());
    }

    public static String queueBaseUrl() {
        return optionalProperty(ConfigKey.QUEUE_BASE_URL, baseUrl());
    }

    public static String performanceId() {
        return property(ConfigKey.PERFORMANCE_ID);
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
                Long.parseLong(performanceId())
        );
    }

    public static int coreP95ThresholdMs() {
        return intProperty(ConfigKey.CORE_P95_THRESHOLD_MS);
    }

    public static int coreP99ThresholdMs() {
        return intProperty(ConfigKey.CORE_P99_THRESHOLD_MS);
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

    public static int performanceSummaryP95ThresholdMs() {
        return intProperty(ConfigKey.PERFORMANCE_SUMMARY_P95_THRESHOLD_MS);
    }

    public static int performanceSummaryP99ThresholdMs() {
        return intProperty(ConfigKey.PERFORMANCE_SUMMARY_P99_THRESHOLD_MS);
    }

    public static int seatStatusP95ThresholdMs() {
        return intProperty(ConfigKey.SEAT_STATUS_P95_THRESHOLD_MS);
    }

    public static int seatStatusP99ThresholdMs() {
        return intProperty(ConfigKey.SEAT_STATUS_P99_THRESHOLD_MS);
    }

    public static int seatSelectP95ThresholdMs() {
        return intProperty(ConfigKey.SEAT_SELECT_P95_THRESHOLD_MS);
    }

    public static int seatSelectP99ThresholdMs() {
        return intProperty(ConfigKey.SEAT_SELECT_P99_THRESHOLD_MS);
    }

    public static int orderCreateP95ThresholdMs() {
        return intProperty(ConfigKey.ORDER_CREATE_P95_THRESHOLD_MS);
    }

    public static int orderCreateP99ThresholdMs() {
        return intProperty(ConfigKey.ORDER_CREATE_P99_THRESHOLD_MS);
    }

    public static int orderGetP95ThresholdMs() {
        return intProperty(ConfigKey.ORDER_GET_P95_THRESHOLD_MS);
    }

    public static int orderGetP99ThresholdMs() {
        return intProperty(ConfigKey.ORDER_GET_P99_THRESHOLD_MS);
    }

    public static int queueP99ThresholdMs() {
        return intProperty(ConfigKey.QUEUE_P99_THRESHOLD_MS);
    }

    public static double technicalFailureThresholdPercent() {
        return doubleProperty(ConfigKey.TECHNICAL_FAILURE_THRESHOLD_PERCENT);
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

    public static boolean dbAuditEnabled() {
        return booleanProperty(ConfigKey.DB_AUDIT_ENABLED);
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
        return exec(session -> session
                .set("performanceId", performanceId())
                .set("seatIdsJson", seatIdsJsonArray()));
    }

    public static ChainBuilder initializeSessionWithNextSeatId() {
        return exec(session -> {
            final Long seatId = nextSeatId();
            return session.set("performanceId", performanceId())
                    .set("seatIdsJson", "[" + seatId + "]")
                    .set("seatId", seatId);
        });
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
        if ("synthetic-jwt".equals(mode)) {
            return exec(session -> {
                final Long memberId = nextSyntheticMember().value();
                return session.set("memberId", memberId)
                        .set("accessToken", createSyntheticJwt(memberId));
            });
        }
        return exec(session -> {
            final int loginIndex = LOGIN_COUNTER.getAndIncrement();
            final String email = property(ConfigKey.LOGIN_EMAIL_PREFIX)
                    + loginIndex + "@" + property(ConfigKey.LOGIN_EMAIL_DOMAIN);
            return session.set("loginEmail", email)
                    .set("loginPassword", property(ConfigKey.LOGIN_PASSWORD));
        }).exec(http("login")
                .post("/api/v1/auth/login")
                .body(StringBody("""
                        {
                          "email": "#{loginEmail}",
                          "password": "#{loginPassword}"
                        }
                        """))
                .check(status().is(200))
                .check(io.gatling.javaapi.core.CoreDsl.jsonPath("$.result").is("SUCCESS"))
                .check(io.gatling.javaapi.core.CoreDsl.jsonPath("$.data.accessToken").saveAs("accessToken")))
                .exec(session -> session.set("memberId", LoadTestTokens.readSubjectAsLong(session.getString("accessToken"))));
    }

    public static ChainBuilder withAdmissionToken() {
        final String mode = property(ConfigKey.ADMISSION_TOKEN_MODE).toLowerCase(Locale.ROOT);
        if ("tokens".equals(mode)) {
            return exec(session -> session.set(
                    "admissionToken",
                    nextFromCsv(ConfigKey.ADMISSION_TOKENS, ADMISSION_TOKEN_COUNTER)
            ));
        }
        if ("synthetic".equals(mode)) {
            return exec(session -> session.set("admissionToken", createSyntheticAdmissionToken(
                    session.getLong("memberId"),
                    Long.parseLong(session.getString("performanceId"))
            )));
        }
        throw new IllegalArgumentException("Unsupported admissionTokenMode: " + mode);
    }

    public static Map<CharSequence, String> authHeaders() {
        return Map.of("Authorization", "Bearer #{accessToken}");
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

    public static Map<CharSequence, String> queueSessionHeaders() {
        return Map.of("X-Queue-Session", "#{queueSessionId}");
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

    private static String nextFromCsv(final ConfigKey key, final AtomicInteger counter) {
        final CsvValues csvValues = CSV_VALUES.computeIfAbsent(key, LoadTestConfig::parseCsv);
        return csvValues.values().get(Math.floorMod(counter.getAndIncrement(), csvValues.values().size()));
    }

    private static String nextAccessToken() {
        final CsvValues csvValues = CSV_VALUES.computeIfAbsent(ConfigKey.ACCESS_TOKENS, ignored -> parseAccessTokens());
        final int index = TOKEN_COUNTER.getAndIncrement();
        if (index >= csvValues.values().size()) {
            throw new IllegalStateException("Access token values exhausted");
        }
        return csvValues.values().get(index);
    }

    private static CsvValues parseCsv(final ConfigKey key) {
        final List<String> values = Stream.of(property(key).split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        if (values.isEmpty()) {
            throw new IllegalStateException("System property must contain at least one value: -D" + key.propertyName());
        }
        return new CsvValues(values);
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

    private static String seatIdsJsonArray() {
        return Stream.of(property(ConfigKey.SEAT_IDS).split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static Long nextSeatId() {
        final List<String> seatIds = Stream.of(property(ConfigKey.SEAT_IDS).split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        if (seatIds.isEmpty()) {
            throw new IllegalStateException("System property must contain at least one value: -DseatIds");
        }
        return Long.parseLong(seatIds.get(Math.floorMod(SEAT_ID_COUNTER.getAndIncrement(), seatIds.size())));
    }

    private static SyntheticMemberId nextSyntheticMember() {
        return new SyntheticMemberId(SYNTHETIC_MEMBER_COUNTER.getAndIncrement());
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

    private static String createSyntheticAdmissionToken(final Long memberId, final Long performanceId) {
        return LoadTestTokens.createAdmissionToken(
                property(ConfigKey.ADMISSION_TOKEN_ISSUER),
                property(ConfigKey.ADMISSION_TOKEN_AUDIENCE),
                property(ConfigKey.ADMISSION_TOKEN_SECRET),
                memberId,
                performanceId,
                Instant.now(),
                intProperty(ConfigKey.ADMISSION_TOKEN_TTL_SECONDS)
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

    private record SyntheticMemberId(Long value) {
    }

    private enum ConfigKey {
        BASE_URL,
        CORE_BASE_URL,
        QUEUE_BASE_URL,
        PERFORMANCE_ID,
        STATUS_POLLS,
        STATUS_POLL_PAUSE_SECONDS,
        STATUS_POLL_PAUSE_JITTER_SECONDS,
        HTTP2_ENABLED,
        USERS,
        DURATION_SECONDS,
        INJECTION_MODE,
        USERS_PER_SECOND,
        TARGET_USERS_PER_SECOND,
        ACCESS_TOKEN_MODE,
        ACCESS_TOKENS,
        ACCESS_TOKENS_FILE,
        ADMISSION_TOKEN_MODE,
        ADMISSION_TOKENS,
        ADMISSION_TOKEN_ISSUER,
        ADMISSION_TOKEN_AUDIENCE,
        ADMISSION_TOKEN_SECRET,
        ADMISSION_TOKEN_TTL_SECONDS,
        LOGIN_EMAIL_PREFIX,
        LOGIN_EMAIL_DOMAIN,
        LOGIN_PASSWORD,
        LOGIN_START_INDEX,
        SEAT_IDS,
        SYNTHETIC_MEMBER_START_ID,
        SYNTHETIC_TOKEN_TTL_SECONDS,
        JWT_ISSUER,
        SYNTHETIC_JWT_ROLE,
        JWT_SECRET,
        DUMP_FAILURE_BODY,
        DUMP_FAILURE_BODY_LIMIT,
        FAILURE_BODY_DIR,
        BOOKING_FEEDER_FILE,
        BOOKING_FEEDER_ROWS,
        BOOKING_SCENARIO,
        NODE_INDEX,
        RESULT_FILE,
        POLLING_TIMEOUT_SECONDS,
        CORE_P95_THRESHOLD_MS,
        CORE_P99_THRESHOLD_MS,
        BOOKING_SEAT_THINK_MIN_MILLIS,
        BOOKING_SEAT_THINK_MAX_MILLIS,
        BOOKING_ORDER_THINK_MIN_MILLIS,
        BOOKING_ORDER_THINK_MAX_MILLIS,
        BOOKING_RETRY_THINK_MIN_MILLIS,
        BOOKING_RETRY_THINK_MAX_MILLIS,
        BOOKING_SEAT_REFRESH_PERCENT,
        BOOKING_DROPOUT_PERCENT,
        PERFORMANCE_SUMMARY_P95_THRESHOLD_MS,
        PERFORMANCE_SUMMARY_P99_THRESHOLD_MS,
        SEAT_STATUS_P95_THRESHOLD_MS,
        SEAT_STATUS_P99_THRESHOLD_MS,
        SEAT_SELECT_P95_THRESHOLD_MS,
        SEAT_SELECT_P99_THRESHOLD_MS,
        ORDER_CREATE_P95_THRESHOLD_MS,
        ORDER_CREATE_P99_THRESHOLD_MS,
        ORDER_GET_P95_THRESHOLD_MS,
        ORDER_GET_P99_THRESHOLD_MS,
        QUEUE_P99_THRESHOLD_MS,
        TECHNICAL_FAILURE_THRESHOLD_PERCENT,
        QUEUE_TIMEOUT_THRESHOLD_PERCENT,
        MAX_CORE_ADMISSIONS_PER_SECOND,
        ADMISSION_RATE_TOLERANCE_PERCENT,
        DB_AUDIT_ENABLED,
        START_AT_EPOCH_MILLIS;

        private String propertyName() {
            return switch (this) {
                case BASE_URL -> "baseUrl";
                case CORE_BASE_URL -> "coreBaseUrl";
                case QUEUE_BASE_URL -> "queueBaseUrl";
                case PERFORMANCE_ID -> "performanceId";
                case STATUS_POLLS -> "statusPolls";
                case STATUS_POLL_PAUSE_SECONDS -> "statusPollPauseSeconds";
                case STATUS_POLL_PAUSE_JITTER_SECONDS -> "statusPollPauseJitterSeconds";
                case HTTP2_ENABLED -> "http2Enabled";
                case USERS -> "users";
                case DURATION_SECONDS -> "durationSeconds";
                case INJECTION_MODE -> "injectionMode";
                case USERS_PER_SECOND -> "usersPerSecond";
                case TARGET_USERS_PER_SECOND -> "targetUsersPerSecond";
                case ACCESS_TOKEN_MODE -> "accessTokenMode";
                case ACCESS_TOKENS -> "accessTokens";
                case ACCESS_TOKENS_FILE -> "accessTokensFile";
                case ADMISSION_TOKEN_MODE -> "admissionTokenMode";
                case ADMISSION_TOKENS -> "admissionTokens";
                case ADMISSION_TOKEN_ISSUER -> "admissionTokenIssuer";
                case ADMISSION_TOKEN_AUDIENCE -> "admissionTokenAudience";
                case ADMISSION_TOKEN_SECRET -> "admissionTokenSecret";
                case ADMISSION_TOKEN_TTL_SECONDS -> "admissionTokenTtlSeconds";
                case LOGIN_EMAIL_PREFIX -> "loginEmailPrefix";
                case LOGIN_EMAIL_DOMAIN -> "loginEmailDomain";
                case LOGIN_PASSWORD -> "loginPassword";
                case LOGIN_START_INDEX -> "loginStartIndex";
                case SEAT_IDS -> "seatIds";
                case SYNTHETIC_MEMBER_START_ID -> "syntheticMemberStartId";
                case SYNTHETIC_TOKEN_TTL_SECONDS -> "syntheticTokenTtlSeconds";
                case JWT_ISSUER -> "jwtIssuer";
                case SYNTHETIC_JWT_ROLE -> "syntheticJwtRole";
                case JWT_SECRET -> "jwtSecret";
                case DUMP_FAILURE_BODY -> "dumpFailureBody";
                case DUMP_FAILURE_BODY_LIMIT -> "dumpFailureBodyLimit";
                case FAILURE_BODY_DIR -> "failureBodyDir";
                case BOOKING_FEEDER_FILE -> "bookingFeederFile";
                case BOOKING_FEEDER_ROWS -> "bookingFeederRows";
                case BOOKING_SCENARIO -> "bookingScenario";
                case NODE_INDEX -> "nodeIndex";
                case RESULT_FILE -> "resultFile";
                case POLLING_TIMEOUT_SECONDS -> "pollingTimeoutSeconds";
                case CORE_P95_THRESHOLD_MS -> "coreP95ThresholdMs";
                case CORE_P99_THRESHOLD_MS -> "coreP99ThresholdMs";
                case BOOKING_SEAT_THINK_MIN_MILLIS -> "bookingSeatThinkMinMillis";
                case BOOKING_SEAT_THINK_MAX_MILLIS -> "bookingSeatThinkMaxMillis";
                case BOOKING_ORDER_THINK_MIN_MILLIS -> "bookingOrderThinkMinMillis";
                case BOOKING_ORDER_THINK_MAX_MILLIS -> "bookingOrderThinkMaxMillis";
                case BOOKING_RETRY_THINK_MIN_MILLIS -> "bookingRetryThinkMinMillis";
                case BOOKING_RETRY_THINK_MAX_MILLIS -> "bookingRetryThinkMaxMillis";
                case BOOKING_SEAT_REFRESH_PERCENT -> "bookingSeatRefreshPercent";
                case BOOKING_DROPOUT_PERCENT -> "bookingDropoutPercent";
                case SEAT_STATUS_P95_THRESHOLD_MS -> "seatStatusP95ThresholdMs";
                case PERFORMANCE_SUMMARY_P95_THRESHOLD_MS -> "performanceSummaryP95ThresholdMs";
                case PERFORMANCE_SUMMARY_P99_THRESHOLD_MS -> "performanceSummaryP99ThresholdMs";
                case SEAT_STATUS_P99_THRESHOLD_MS -> "seatStatusP99ThresholdMs";
                case SEAT_SELECT_P95_THRESHOLD_MS -> "seatSelectP95ThresholdMs";
                case SEAT_SELECT_P99_THRESHOLD_MS -> "seatSelectP99ThresholdMs";
                case ORDER_CREATE_P95_THRESHOLD_MS -> "orderCreateP95ThresholdMs";
                case ORDER_CREATE_P99_THRESHOLD_MS -> "orderCreateP99ThresholdMs";
                case ORDER_GET_P95_THRESHOLD_MS -> "orderGetP95ThresholdMs";
                case ORDER_GET_P99_THRESHOLD_MS -> "orderGetP99ThresholdMs";
                case QUEUE_P99_THRESHOLD_MS -> "queueP99ThresholdMs";
                case TECHNICAL_FAILURE_THRESHOLD_PERCENT -> "technicalFailureThresholdPercent";
                case QUEUE_TIMEOUT_THRESHOLD_PERCENT -> "queueTimeoutThresholdPercent";
                case MAX_CORE_ADMISSIONS_PER_SECOND -> "maxCoreAdmissionsPerSecond";
                case ADMISSION_RATE_TOLERANCE_PERCENT -> "admissionRateTolerancePercent";
                case DB_AUDIT_ENABLED -> "dbAuditEnabled";
                case START_AT_EPOCH_MILLIS -> "startAtEpochMillis";
            };
        }

        private String defaultValue() {
            return switch (this) {
                case BASE_URL -> "http://52.237.82.8:18090/legacy-queue";
                case CORE_BASE_URL, QUEUE_BASE_URL -> null;
                case PERFORMANCE_ID -> "1";
                case STATUS_POLLS -> "3";
                case STATUS_POLL_PAUSE_SECONDS -> "1";
                case STATUS_POLL_PAUSE_JITTER_SECONDS -> "0";
                case HTTP2_ENABLED -> "false";
                case USERS -> "10";
                case DURATION_SECONDS -> "10";
                case INJECTION_MODE -> "ramp-users";
                case USERS_PER_SECOND -> "1.0";
                case TARGET_USERS_PER_SECOND -> "10.0";
                case ACCESS_TOKEN_MODE -> "login";
                case ADMISSION_TOKEN_MODE -> "synthetic";
                case ADMISSION_TOKEN_ISSUER -> "ticket-queue";
                case ADMISSION_TOKEN_AUDIENCE -> "ticket-api";
                case ADMISSION_TOKEN_SECRET -> "0123456789abcdef0123456789abcdef";
                case ADMISSION_TOKEN_TTL_SECONDS -> "300";
                case LOGIN_EMAIL_PREFIX -> "loadtest";
                case LOGIN_EMAIL_DOMAIN -> "test.com";
                case LOGIN_PASSWORD -> "password1234";
                case LOGIN_START_INDEX -> "1";
                case SEAT_IDS -> "1";
                case SYNTHETIC_MEMBER_START_ID -> "1";
                case SYNTHETIC_TOKEN_TTL_SECONDS -> "3600";
                case JWT_ISSUER -> "ticket";
                case SYNTHETIC_JWT_ROLE -> "MEMBER";
                case DUMP_FAILURE_BODY -> "false";
                case DUMP_FAILURE_BODY_LIMIT -> "1";
                case FAILURE_BODY_DIR -> "../../distributed-results-join/_latest/failure-bodies";
                case BOOKING_FEEDER_FILE -> "build/booking-feeder.csv";
                case BOOKING_SCENARIO -> "TICKET_OPEN_END_TO_END";
                case NODE_INDEX -> "0";
                case RESULT_FILE -> "../../distributed-results-join/_latest/booking-results.csv";
                case POLLING_TIMEOUT_SECONDS -> "300";
                case CORE_P95_THRESHOLD_MS -> "2000";
                case CORE_P99_THRESHOLD_MS -> "3000";
                case BOOKING_SEAT_THINK_MIN_MILLIS -> "1000";
                case BOOKING_SEAT_THINK_MAX_MILLIS -> "3000";
                case BOOKING_ORDER_THINK_MIN_MILLIS -> "2000";
                case BOOKING_ORDER_THINK_MAX_MILLIS -> "6000";
                case BOOKING_RETRY_THINK_MIN_MILLIS -> "500";
                case BOOKING_RETRY_THINK_MAX_MILLIS -> "2000";
                case BOOKING_SEAT_REFRESH_PERCENT -> "25.0";
                case BOOKING_DROPOUT_PERCENT -> "10.0";
                case SEAT_STATUS_P95_THRESHOLD_MS -> "300";
                case SEAT_STATUS_P99_THRESHOLD_MS -> "700";
                case PERFORMANCE_SUMMARY_P95_THRESHOLD_MS -> "300";
                case PERFORMANCE_SUMMARY_P99_THRESHOLD_MS -> "700";
                case SEAT_SELECT_P95_THRESHOLD_MS -> "500";
                case SEAT_SELECT_P99_THRESHOLD_MS -> "1000";
                case ORDER_CREATE_P95_THRESHOLD_MS -> "800";
                case ORDER_CREATE_P99_THRESHOLD_MS -> "1500";
                case ORDER_GET_P95_THRESHOLD_MS -> "500";
                case ORDER_GET_P99_THRESHOLD_MS -> "1000";
                case QUEUE_P99_THRESHOLD_MS -> "2000";
                case TECHNICAL_FAILURE_THRESHOLD_PERCENT -> "1.0";
                case QUEUE_TIMEOUT_THRESHOLD_PERCENT -> "0.0";
                case MAX_CORE_ADMISSIONS_PER_SECOND -> "0";
                case ADMISSION_RATE_TOLERANCE_PERCENT -> "10.0";
                case DB_AUDIT_ENABLED -> "false";
                case START_AT_EPOCH_MILLIS -> "0";
                case ACCESS_TOKENS, ACCESS_TOKENS_FILE, ADMISSION_TOKENS, JWT_SECRET, BOOKING_FEEDER_ROWS -> null;
            };
        }
    }
}
