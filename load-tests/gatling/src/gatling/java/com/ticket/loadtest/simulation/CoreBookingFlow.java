package com.ticket.loadtest.simulation;

import com.ticket.loadtest.BookingEvidenceRecorder;
import com.ticket.loadtest.BookingResultRecorder;
import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;

import java.nio.file.Path;
import java.time.Instant;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

final class CoreBookingFlow {

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

    private static ChainBuilder recordCoreAdmission() {
        return exec(session -> {
            final Instant admittedAt = BookingEvidenceRecorder.recordCoreAdmission(
                    Path.of(LoadTestConfig.resultFile()),
                    session.getString("bookingScenarioName"),
                    LoadTestConfig.nodeIndex()
            );
            return session
                    .set("coreAdmittedAt", admittedAt.toString())
                    .set("lastStep", "CORE_ADMITTED");
        });
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