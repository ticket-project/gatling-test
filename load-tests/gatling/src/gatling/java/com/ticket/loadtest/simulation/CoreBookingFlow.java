package com.ticket.loadtest.simulation;

import com.ticket.loadtest.BookingEvidenceRecorder;
import com.ticket.loadtest.BookingResultRecorder;
import com.ticket.loadtest.LoadTestConfig;
import com.ticket.loadtest.RealisticSeatSelection;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

final class CoreBookingFlow {
    private static final String SELECT_REJECTION_CODE = "E4001";
    private static final Set<String> ORDER_REJECTION_CODES = Set.of("E5000", "E5001", "E6000", "E6003");

    private CoreBookingFlow() {
    }

    static ChainBuilder initializeSession(final String scenario) {
        return initializeSession(scenario, false);
    }

    static ChainBuilder initializeRealisticSession(final String scenario) {
        return initializeSession(scenario, true);
    }

    private static ChainBuilder initializeSession(final String scenario, final boolean dynamicSeatSelection) {
        return exec(session -> {
            final Instant startedAt = BookingEvidenceRecorder.recordStarted(
                    Path.of(LoadTestConfig.resultFile()),
                    scenario,
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId")
            );
            Session initialized = session
                    .removeAll("terminalResult", "terminalHttpStatus", "orderKey", "coreAdmittedAt")
                    .set("bookingScenarioName", scenario)
                    .set("performanceId", LoadTestConfig.performanceId())
                    .set("flowStartedAt", startedAt.toString())
                    .set("lastStep", "INITIALIZED")
                    .set("dynamicSeatSelection", dynamicSeatSelection)
                    .set("selectAttemptCount", 0);
            if (dynamicSeatSelection) {
                return initialized
                        .removeAll("seatId", "seatIdsJson", "availableSeatIds", "selectConflict")
                        .set("attemptedSeatIds", Set.<Long>of());
            }
            return initialized
                    .set("seatIdsJson", "[" + session.getLong("seatId") + "]");
        });
    }

    static ChainBuilder successfulFlow(final String scenario, final boolean verifyCreatedOrder) {
        ChainBuilder flow = recordCoreAdmission()
                .exec(fetchPerformanceSummary())
                .exec(captureExpectedStatus("PERFORMANCE_SUMMARY", "performanceSummaryHttpStatus", 200))
                .exec(fetchSeatStatus())
                .exec(captureExpectedStatus("SEAT_STATUS", "seatStatusHttpStatus", 200))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(selectSeat())
                                .exec(captureExpectedStatus("SELECT_SEAT", "selectHttpStatus", 200))
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(createOrder())
                                .exec(captureExpectedStatus("CREATE_ORDER", "createOrderHttpStatus", 201))
                                .exec(doIf(CoreBookingFlow::canContinue).then(resolveOrderKey()))
                ));

        if (verifyCreatedOrder) {
            flow = flow.exec(doIf(CoreBookingFlow::canContinue).then(
                    exec(fetchOrder())
                            .exec(captureExpectedStatus("GET_ORDER", "orderHttpStatus", 200))
                            .exec(doIf(CoreBookingFlow::canContinue).then(validateOrderState()))
            ));
        }
        return flow.exec(recordTerminal(scenario, verifyCreatedOrder));
    }

    static ChainBuilder realisticFlow(final String scenario) {
        return realisticFlow(scenario, true);
    }

    static ChainBuilder realisticFlowWithoutAdmission(final String scenario) {
        return realisticFlow(scenario, false);
    }

    private static ChainBuilder realisticFlow(final String scenario, final boolean includeAdmissionToken) {
        return recordCoreAdmission()
                .exec(fetchPerformanceSummary())
                .exec(captureExpectedStatus("PERFORMANCE_SUMMARY", "performanceSummaryHttpStatus", 200))
                .exec(fetchSeatStatus(includeAdmissionToken))
                .exec(captureExpectedStatus("SEAT_STATUS", "seatStatusHttpStatus", 200))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        pause(LoadTestConfig.bookingSeatThinkMin(), LoadTestConfig.bookingSeatThinkMax())
                ))
                .exec(doIf(session -> canContinue(session) && shouldRefreshSeatStatus()).then(
                        exec(fetchSeatStatus(includeAdmissionToken))
                                .exec(captureExpectedStatus("SEAT_STATUS", "seatStatusHttpStatus", 200))
                ))
                .exec(doIf(session -> canContinue(session) && isDynamicSeatSelection(session)).then(
                        chooseAvailableSeat()
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(selectSeatAllowingConflict(includeAdmissionToken))
                                .exec(classifySelectAttempt())
                ))
                .exec(doIf(CoreBookingFlow::hasSelectConflict).then(
                        recordSeatSelectionConflict()
                ))
                .asLongAs(CoreBookingFlow::shouldRetrySeatSelection).on(
                        pause(LoadTestConfig.bookingRetryThinkMin(), LoadTestConfig.bookingRetryThinkMax())
                                .exec(fetchSeatStatus(includeAdmissionToken))
                                .exec(captureExpectedStatus("SEAT_STATUS", "seatStatusHttpStatus", 200))
                                .exec(doIf(session -> canContinue(session) && isDynamicSeatSelection(session)).then(
                                        chooseAvailableSeat()
                                ))
                                .exec(doIf(CoreBookingFlow::canContinue).then(
                                        exec(selectSeatAllowingConflict(includeAdmissionToken))
                                                .exec(classifySelectAttempt())
                                ))
                                .exec(doIf(CoreBookingFlow::hasSelectConflict).then(
                                        recordSeatSelectionConflict()
                                ))
                )
                .exec(doIf(CoreBookingFlow::hasSelectConflict).then(
                        markSelectBusinessRejection()
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        pause(LoadTestConfig.bookingOrderThinkMin(), LoadTestConfig.bookingOrderThinkMax())
                ))
                .exec(doIf(session -> canContinue(session) && shouldDropBeforeOrder()).then(
                        markUserDropout()
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(createOrderAllowingConflict(includeAdmissionToken))
                                .exec(classifyOrderAttempt())
                                .exec(doIf(CoreBookingFlow::canContinue).then(resolveOrderKey()))
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        pause(LoadTestConfig.bookingRetryThinkMin(), LoadTestConfig.bookingRetryThinkMax())
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(fetchOrder())
                                .exec(captureExpectedStatus("GET_ORDER", "orderHttpStatus", 200))
                                .exec(doIf(CoreBookingFlow::canContinue).then(validateOrderState()))
                ))
                .exec(recordTerminal(scenario, true));
    }

    private static ChainBuilder recordCoreAdmission() {
        return exec(session -> {
            final Instant admittedAt = BookingEvidenceRecorder.recordCoreAdmission(
                    Path.of(LoadTestConfig.resultFile()),
                    session.getString("bookingScenarioName"),
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId")
            );
            return session
                    .set("coreAdmittedAt", admittedAt.toString())
                    .set("lastStep", "CORE_ADMITTED");
        });
    }

    private static ChainBuilder recordSeatSelectionConflict() {
        return exec(session -> {
            BookingEvidenceRecorder.recordSeatSelectionConflict(
                    Path.of(LoadTestConfig.resultFile()),
                    session.getString("bookingScenarioName"),
                    LoadTestConfig.nodeIndex()
            );
            return session;
        });
    }

    private static ChainBuilder fetchPerformanceSummary() {
        return exec(session -> session.remove("performanceSummaryHttpStatus").set("lastStep", "PERFORMANCE_SUMMARY"))
                .exec(http("performance summary")
                        .get("/api/v1/performances/#{performanceId}/summary")
                        .headers(bookingHeaders(false))
                        .check(status().saveAs("performanceSummaryHttpStatus"))
                        .check(status().is(200)));
    }

    private static ChainBuilder fetchSeatStatus() {
        return fetchSeatStatus(true);
    }

    private static ChainBuilder fetchSeatStatus(final boolean includeAdmissionToken) {
        return exec(session -> session.removeAll("seatStatusHttpStatus", "availableSeatIds")
                        .set("lastStep", "SEAT_STATUS"))
                .exec(http("seat status")
                        .get("/api/v1/performances/#{performanceId}/seats/status")
                        .headers(bookingHeaders(includeAdmissionToken))
                        .check(status().saveAs("seatStatusHttpStatus"))
                        .check(status().is(200))
                        .check(jsonPath("$.data.seats[?(@.status == 'AVAILABLE')].seatId")
                                .ofLong().findAll().optional().saveAs("availableSeatIds")));
    }

    private static ChainBuilder chooseAvailableSeat() {
        return exec(session -> {
            final List<Long> available = session.contains("availableSeatIds")
                    ? session.<Long>getList("availableSeatIds")
                    : List.of();
            final Set<Long> attempted = session.contains("attemptedSeatIds")
                    ? session.<Long>getSet("attemptedSeatIds")
                    : Set.of();
            final List<Long> candidates = RealisticSeatSelection.availableCandidates(available, attempted);
            if (candidates.isEmpty()) {
                return session
                        .set("selectConflict", false)
                        .set("terminalResult", "BUSINESS_REJECTED_NO_AVAILABLE_SEAT")
                        .set("terminalHttpStatus", 409)
                        .set("lastStep", "SELECT_SEAT");
            }

            final long seatId = RealisticSeatSelection.chooseSeat(candidates);
            final Set<Long> updatedAttempts = new HashSet<>(attempted);
            updatedAttempts.add(seatId);
            return session
                    .set("attemptedSeatIds", Set.copyOf(updatedAttempts))
                    .set("seatId", seatId)
                    .set("seatIdsJson", "[" + seatId + "]")
                    .set("lastStep", "SELECT_SEAT");
        });
    }

    private static ChainBuilder selectSeat() {
        return exec(session -> session.remove("selectHttpStatus").set("lastStep", "SELECT_SEAT"))
                .exec(http("select seat")
                        .post("/api/v1/performances/#{performanceId}/seats/#{seatId}/select")
                        .headers(LoadTestConfig.authAndAdmissionHeaders())
                        .check(status().saveAs("selectHttpStatus"))
                        .check(status().is(200)));
    }

    private static ChainBuilder selectSeatAllowingConflict(final boolean includeAdmissionToken) {
        return exec(session -> session
                .removeAll("selectHttpStatus", "selectErrorCode")
                .set("lastStep", "SELECT_SEAT")
                .set("selectAttemptCount", optionalInt(session, "selectAttemptCount") + 1))
                .exec(http("select seat")
                        .post("/api/v1/performances/#{performanceId}/seats/#{seatId}/select")
                        .headers(bookingHeaders(includeAdmissionToken))
                        .check(status().saveAs("selectHttpStatus"))
                        .check(jsonPath("$.error.code").optional().saveAs("selectErrorCode"))
                        .check(status().in(200, 409))
                        .checkIf((response, session) -> response.status().code() == 409).then(
                                jsonPath("$.error.code").is(SELECT_REJECTION_CODE)
                        ));
    }

    private static ChainBuilder classifySelectAttempt() {
        return exec(session -> {
            if (!session.contains("selectHttpStatus")) {
                return terminalFailure(session, "TECHNICAL_SELECT_SEAT_NO_RESPONSE", "SELECT_SEAT", 0);
            }
            final int httpStatus = session.getInt("selectHttpStatus");
            if (httpStatus == 200) {
                return session.set("selectConflict", false);
            }
            final boolean conflict = httpStatus == 409
                    && SELECT_REJECTION_CODE.equals(optionalString(session, "selectErrorCode"));
            if (conflict) {
                return session.set("selectConflict", true);
            }
            return terminalFailure(session, "TECHNICAL_SELECT_SEAT_HTTP_" + httpStatus,
                    "SELECT_SEAT", httpStatus);
        });
    }

    private static ChainBuilder markSelectBusinessRejection() {
        return exec(session -> session
                .set("terminalResult", "BUSINESS_REJECTED_" + SELECT_REJECTION_CODE)
                .set("terminalHttpStatus", optionalInt(session, "selectHttpStatus"))
                .set("lastStep", "SELECT_SEAT"));
    }

    private static ChainBuilder createOrder() {
        return exec(session -> session
                .removeAll("createOrderHttpStatus", "orderKeyHeader", "orderKeyBody")
                .set("lastStep", "CREATE_ORDER"))
                .exec(http("create order")
                        .post("/api/v1/orders")
                        .headers(LoadTestConfig.authAndAdmissionHeaders())
                        .body(StringBody("""
                                {
                                  "performanceId": #{performanceId},
                                  "seatIds": #{seatIdsJson}
                                }
                                """))
                        .check(status().saveAs("createOrderHttpStatus"))
                        .check(status().is(201))
                        .check(header("X-Order-Key").optional().saveAs("orderKeyHeader"))
                        .check(jsonPath("$.data.orderKey").optional().saveAs("orderKeyBody")));
    }

    private static ChainBuilder createOrderAllowingConflict(final boolean includeAdmissionToken) {
        return exec(session -> session
                .removeAll("createOrderHttpStatus", "orderKeyHeader", "orderKeyBody", "orderErrorCode")
                .set("lastStep", "CREATE_ORDER"))
                .exec(http("create order")
                        .post("/api/v1/orders")
                        .headers(bookingHeaders(includeAdmissionToken))
                        .body(StringBody("""
                                {
                                  "performanceId": #{performanceId},
                                  "seatIds": #{seatIdsJson}
                                }
                                """))
                        .check(status().saveAs("createOrderHttpStatus"))
                        .check(status().in(201, 409))
                        .check(header("X-Order-Key").optional().saveAs("orderKeyHeader"))
                        .check(jsonPath("$.data.orderKey").optional().saveAs("orderKeyBody"))
                        .check(jsonPath("$.error.code").optional().saveAs("orderErrorCode"))
                        .checkIf((response, session) -> response.status().code() == 409).then(
                                jsonPath("$.error.code").in(ORDER_REJECTION_CODES.stream().toList())
                        ));
    }

    private static ChainBuilder classifyOrderAttempt() {
        return exec(session -> {
            if (!session.contains("createOrderHttpStatus")) {
                return terminalFailure(session, "TECHNICAL_CREATE_ORDER_NO_RESPONSE", "CREATE_ORDER", 0);
            }
            final int httpStatus = session.getInt("createOrderHttpStatus");
            if (httpStatus == 201) {
                return session;
            }
            final String errorCode = optionalString(session, "orderErrorCode");
            if (httpStatus == 409 && ORDER_REJECTION_CODES.contains(errorCode)) {
                return session
                        .set("terminalResult", "BUSINESS_REJECTED_" + errorCode)
                        .set("terminalHttpStatus", httpStatus)
                        .set("lastStep", "CREATE_ORDER");
            }
            return terminalFailure(session, "TECHNICAL_CREATE_ORDER_HTTP_" + httpStatus,
                    "CREATE_ORDER", httpStatus);
        });
    }

    private static ChainBuilder markUserDropout() {
        return exec(session -> session
                .set("terminalResult", "USER_DROPPED_BEFORE_ORDER")
                .set("terminalHttpStatus", optionalInt(session, "selectHttpStatus"))
                .set("lastStep", "BEFORE_CREATE_ORDER"));
    }

    private static ChainBuilder resolveOrderKey() {
        return exec(session -> {
            final String headerOrderKey = optionalString(session, "orderKeyHeader");
            final String bodyOrderKey = optionalString(session, "orderKeyBody");
            if (headerOrderKey.isBlank() && bodyOrderKey.isBlank()) {
                return terminalFailure(session, "ORDER_KEY_MISSING", "CREATE_ORDER",
                        optionalInt(session, "createOrderHttpStatus"));
            }
            if (!headerOrderKey.isBlank() && !bodyOrderKey.isBlank() && !headerOrderKey.equals(bodyOrderKey)) {
                return terminalFailure(session, "ORDER_KEY_MISMATCH", "CREATE_ORDER",
                        optionalInt(session, "createOrderHttpStatus"));
            }
            return session.set("orderKey", headerOrderKey.isBlank() ? bodyOrderKey : headerOrderKey);
        });
    }

    private static ChainBuilder fetchOrder() {
        return exec(session -> session
                .removeAll("orderHttpStatus", "orderState")
                .set("lastStep", "GET_ORDER"))
                .exec(http("get order")
                        .get("/api/v1/orders/#{orderKey}")
                        .headers(bookingHeaders(false))
                        .check(status().saveAs("orderHttpStatus"))
                        .check(status().is(200))
                        .check(jsonPath("$.data.status").optional().saveAs("orderState")));
    }

    private static ChainBuilder validateOrderState() {
        return exec(session -> "PENDING".equals(optionalString(session, "orderState"))
                ? session
                : terminalFailure(session, "ORDER_STATE_CONTRACT_FAILURE", "GET_ORDER",
                        optionalInt(session, "orderHttpStatus")));
    }

    private static ChainBuilder captureExpectedStatus(
            final String step,
            final String statusName,
            final int expectedStatus
    ) {
        return exec(session -> {
            if (session.contains("terminalResult")) {
                return session;
            }
            if (!session.contains(statusName)) {
                return terminalFailure(session, "TECHNICAL_" + step + "_NO_RESPONSE", step, 0);
            }
            final int actualStatus = session.getInt(statusName);
            if (actualStatus != expectedStatus) {
                return terminalFailure(session, "TECHNICAL_" + step + "_HTTP_" + actualStatus,
                        step, actualStatus);
            }
            return session;
        });
    }

    private static ChainBuilder recordTerminal(final String scenario, final boolean verifyCreatedOrder) {
        return exec(session -> {
            final String result = session.contains("terminalResult")
                    ? session.getString("terminalResult")
                    : "SUCCESS";
            final String lastStep = session.contains("terminalResult")
                    ? session.getString("lastStep")
                    : "COMPLETED";
            final int httpStatus = session.contains("terminalHttpStatus")
                    ? session.getInt("terminalHttpStatus")
                    : optionalInt(session, verifyCreatedOrder ? "orderHttpStatus" : "createOrderHttpStatus");
            BookingResultRecorder.append(
                    Path.of(LoadTestConfig.resultFile()),
                    scenario,
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId"),
                    optionalLong(session, "seatId"),
                    optionalString(session, "orderKey"),
                    httpStatus,
                    result,
                    lastStep,
                    optionalString(session, "flowStartedAt"),
                    optionalString(session, "coreAdmittedAt"),
                    terminalErrorCode(session),
                    optionalInt(session, "selectAttemptCount")
            );
            return session;
        });
    }

    private static boolean hasSelectConflict(final Session session) {
        return canContinue(session)
                && session.contains("selectConflict")
                && session.getBoolean("selectConflict");
    }

    private static boolean shouldRetrySeatSelection(final Session session) {
        final int maxAttempts = RealisticSeatSelection.maxAttempts(isDynamicSeatSelection(session));
        return hasSelectConflict(session) && optionalInt(session, "selectAttemptCount") < maxAttempts;
    }


    private static boolean isDynamicSeatSelection(final Session session) {
        return session.contains("dynamicSeatSelection") && session.getBoolean("dynamicSeatSelection");
    }

    private static Map<CharSequence, String> bookingHeaders(final boolean includeAdmissionToken) {
        final Map<CharSequence, String> headers = new HashMap<>(includeAdmissionToken
                ? LoadTestConfig.authAndAdmissionHeaders()
                : LoadTestConfig.authHeaders());
        headers.putAll(LoadTestConfig.bookingCorrelationHeaders());
        return Map.copyOf(headers);
    }

    private static String terminalErrorCode(final Session session) {
        final String orderErrorCode = optionalString(session, "orderErrorCode");
        return orderErrorCode.isBlank() ? optionalString(session, "selectErrorCode") : orderErrorCode;
    }

    private static boolean shouldRefreshSeatStatus() {
        return chance(LoadTestConfig.bookingSeatRefreshPercent());
    }

    private static boolean shouldDropBeforeOrder() {
        return chance(LoadTestConfig.bookingDropoutPercent());
    }

    private static boolean chance(final double percent) {
        return percent > 0.0 && ThreadLocalRandom.current().nextDouble(100.0) < percent;
    }

    private static boolean canContinue(final Session session) {
        return !session.contains("terminalResult");
    }

    private static Session terminalFailure(
            final Session session,
            final String result,
            final String lastStep,
            final int httpStatus
    ) {
        return session
                .set("terminalResult", result)
                .set("terminalHttpStatus", httpStatus)
                .set("lastStep", lastStep)
                .markAsFailed();
    }

    private static int optionalInt(final Session session, final String key) {
        return session.contains(key) ? session.getInt(key) : 0;
    }

    private static long optionalLong(final Session session, final String key) {
        return session.contains(key) ? session.getLong(key) : 0L;
    }

    private static String optionalString(final Session session, final String key) {
        return session.contains(key) ? session.getString(key) : "";
    }
}
