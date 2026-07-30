package com.ticket.loadtest.simulation;

import com.ticket.loadtest.BookingEvidenceRecorder;
import com.ticket.loadtest.BookingResultRecorder;
import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;

import java.nio.file.Path;
import java.time.Instant;
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
        return exec(session -> {
            final Instant startedAt = BookingEvidenceRecorder.recordStarted(
                    Path.of(LoadTestConfig.resultFile()),
                    scenario,
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId")
            );
            return session
                    .removeAll("terminalResult", "terminalHttpStatus", "orderKey", "coreAdmittedAt")
                    .set("bookingScenarioName", scenario)
                    .set("performanceId", LoadTestConfig.performanceId())
                    .set("seatIdsJson", "[" + session.getLong("seatId") + "]")
                    .set("flowStartedAt", startedAt.toString())
                    .set("lastStep", "INITIALIZED");
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
        return recordCoreAdmission()
                .exec(fetchPerformanceSummary())
                .exec(captureExpectedStatus("PERFORMANCE_SUMMARY", "performanceSummaryHttpStatus", 200))
                .exec(fetchSeatStatus())
                .exec(captureExpectedStatus("SEAT_STATUS", "seatStatusHttpStatus", 200))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        pause(LoadTestConfig.bookingSeatThinkMin(), LoadTestConfig.bookingSeatThinkMax())
                ))
                .exec(doIf(session -> canContinue(session) && shouldRefreshSeatStatus()).then(
                        exec(fetchSeatStatus())
                                .exec(captureExpectedStatus("SEAT_STATUS", "seatStatusHttpStatus", 200))
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(selectSeatAllowingConflict())
                                .exec(classifySelectAttempt())
                ))
                .exec(doIf(CoreBookingFlow::hasSelectConflict).then(
                        pause(LoadTestConfig.bookingRetryThinkMin(), LoadTestConfig.bookingRetryThinkMax())
                ))
                .exec(doIf(CoreBookingFlow::hasSelectConflict).then(
                        exec(fetchSeatStatus())
                                .exec(captureExpectedStatus("SEAT_STATUS", "seatStatusHttpStatus", 200))
                ))
                .exec(doIf(CoreBookingFlow::hasSelectConflict).then(
                        pause(LoadTestConfig.bookingSeatThinkMin(), LoadTestConfig.bookingSeatThinkMax())
                ))
                .exec(doIf(CoreBookingFlow::hasSelectConflict).then(
                        exec(selectSeatAllowingConflict())
                                .exec(classifySelectAttempt())
                ))
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
                        exec(createOrderAllowingConflict())
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

    private static ChainBuilder fetchPerformanceSummary() {
        return exec(session -> session.remove("performanceSummaryHttpStatus").set("lastStep", "PERFORMANCE_SUMMARY"))
                .exec(http("performance summary")
                        .get("/api/v1/performances/#{performanceId}/summary")
                        .headers(LoadTestConfig.authHeaders())
                        .check(status().saveAs("performanceSummaryHttpStatus"))
                        .check(status().is(200)));
    }

    private static ChainBuilder fetchSeatStatus() {
        return exec(session -> session.remove("seatStatusHttpStatus").set("lastStep", "SEAT_STATUS"))
                .exec(http("seat status")
                        .get("/api/v1/performances/#{performanceId}/seats/status")
                        .headers(LoadTestConfig.authAndAdmissionHeaders())
                        .check(status().saveAs("seatStatusHttpStatus"))
                        .check(status().is(200)));
    }

    private static ChainBuilder selectSeat() {
        return exec(session -> session.remove("selectHttpStatus").set("lastStep", "SELECT_SEAT"))
                .exec(http("select seat")
                        .post("/api/v1/performances/#{performanceId}/seats/#{seatId}/select")
                        .headers(LoadTestConfig.authAndAdmissionHeaders())
                        .check(status().saveAs("selectHttpStatus"))
                        .check(status().is(200)));
    }

    private static ChainBuilder selectSeatAllowingConflict() {
        return exec(session -> session
                .removeAll("selectHttpStatus", "selectErrorCode")
                .set("lastStep", "SELECT_SEAT"))
                .exec(http("select seat")
                        .post("/api/v1/performances/#{performanceId}/seats/#{seatId}/select")
                        .headers(LoadTestConfig.authAndAdmissionHeaders())
                        .check(status().saveAs("selectHttpStatus"))
                        .check(status().in(200, 409))
                        .check(jsonPath("$.error.code").optional().saveAs("selectErrorCode")));
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

    private static ChainBuilder createOrderAllowingConflict() {
        return exec(session -> session
                .removeAll("createOrderHttpStatus", "orderKeyHeader", "orderKeyBody", "orderErrorCode")
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
                        .check(status().in(201, 409))
                        .check(header("X-Order-Key").optional().saveAs("orderKeyHeader"))
                        .check(jsonPath("$.data.orderKey").optional().saveAs("orderKeyBody"))
                        .check(jsonPath("$.error.code").optional().saveAs("orderErrorCode")));
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
                        .headers(LoadTestConfig.authHeaders())
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
                    session.getLong("seatId"),
                    optionalString(session, "orderKey"),
                    httpStatus,
                    result,
                    lastStep,
                    optionalString(session, "flowStartedAt"),
                    optionalString(session, "coreAdmittedAt")
            );
            return session;
        });
    }

    private static boolean hasSelectConflict(final Session session) {
        return canContinue(session)
                && session.contains("selectConflict")
                && session.getBoolean("selectConflict");
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

    private static String optionalString(final Session session, final String key) {
        return session.contains(key) ? session.getString(key) : "";
    }
}
