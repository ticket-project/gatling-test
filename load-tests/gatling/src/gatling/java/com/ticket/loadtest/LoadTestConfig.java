package com.ticket.loadtest;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.Session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public final class LoadTestConfig {
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

    public static String bookingScenario() {
        return property(ConfigKey.BOOKING_SCENARIO);
    }

    public static int nodeIndex() {
        return nonNegativeIntProperty(ConfigKey.NODE_INDEX);
    }

    public static String resultFile() {
        return property(ConfigKey.RESULT_FILE);
    }

    public static int pollingTimeoutSeconds() {
        return nonNegativeIntProperty(ConfigKey.POLLING_TIMEOUT_SECONDS);
    }

    public static Iterator<Map<String, Object>> bookingFeeder() {
        return BookingFeeder.load(Path.of(bookingFeederFile()), bookingScenario(), users(), Long.parseLong(performanceId()));
    }

    public static OpenInjectionStep injection() {
        final int users = intProperty(ConfigKey.USERS);
        final int durationSeconds = intProperty(ConfigKey.DURATION_SECONDS);
        final String mode = property(ConfigKey.INJECTION_MODE).toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "at-once-users" -> atOnceUsers(users);
            case "constant-users-per-sec" -> constantUsersPerSec(doubleProperty(ConfigKey.USERS_PER_SECOND))
                    .during(Duration.ofSeconds(durationSeconds));
            case "ramp-users-per-sec" -> rampUsersPerSec(doubleProperty(ConfigKey.USERS_PER_SECOND))
                    .to(doubleProperty(ConfigKey.TARGET_USERS_PER_SECOND))
                    .during(Duration.ofSeconds(durationSeconds));
            default -> rampUsers(users).during(Duration.ofSeconds(durationSeconds));
        };
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

    private static String nextFromCsv(final ConfigKey key, final AtomicInteger counter) {
        final CsvValues csvValues = CSV_VALUES.computeIfAbsent(key, LoadTestConfig::parseCsv);
        return csvValues.values().get(Math.floorMod(counter.getAndIncrement(), csvValues.values().size()));
    }

    private static String nextAccessToken() {
        final CsvValues csvValues = CSV_VALUES.computeIfAbsent(ConfigKey.ACCESS_TOKENS, ignored -> parseAccessTokens());
        return csvValues.values().get(Math.floorMod(TOKEN_COUNTER.getAndIncrement(), csvValues.values().size()));
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
        return new CsvValues(LoadTestTokenValues.fromCsvOrFile(
                System.getProperty(ConfigKey.ACCESS_TOKENS.propertyName()),
                optionalProperty(ConfigKey.ACCESS_TOKENS_FILE, ""),
                ConfigKey.ACCESS_TOKENS.propertyName()
        ));
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
        BOOKING_SCENARIO,
        NODE_INDEX,
        RESULT_FILE,
        POLLING_TIMEOUT_SECONDS;

        private String propertyName() {
            return switch (this) {
                case BASE_URL -> "baseUrl";
                case CORE_BASE_URL -> "coreBaseUrl";
                case QUEUE_BASE_URL -> "queueBaseUrl";
                case PERFORMANCE_ID -> "performanceId";
                case STATUS_POLLS -> "statusPolls";
                case STATUS_POLL_PAUSE_SECONDS -> "statusPollPauseSeconds";
                case STATUS_POLL_PAUSE_JITTER_SECONDS -> "statusPollPauseJitterSeconds";
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
                case BOOKING_SCENARIO -> "bookingScenario";
                case NODE_INDEX -> "nodeIndex";
                case RESULT_FILE -> "resultFile";
                case POLLING_TIMEOUT_SECONDS -> "pollingTimeoutSeconds";
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
                case FAILURE_BODY_DIR -> "build/reports/failure-bodies";
                case BOOKING_FEEDER_FILE -> "build/booking-feeder.csv";
                case BOOKING_SCENARIO -> "TICKET_OPEN_END_TO_END";
                case NODE_INDEX -> "0";
                case RESULT_FILE -> "build/reports/booking-results.csv";
                case POLLING_TIMEOUT_SECONDS -> "300";
                case ACCESS_TOKENS, ACCESS_TOKENS_FILE, ADMISSION_TOKENS, JWT_SECRET -> null;
            };
        }
    }
}
