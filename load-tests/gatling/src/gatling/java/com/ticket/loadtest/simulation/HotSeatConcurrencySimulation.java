package com.ticket.loadtest.simulation;

import com.ticket.loadtest.BookingEvidenceRecorder;
import com.ticket.loadtest.BookingResultRecorder;
import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.dummy;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class HotSeatConcurrencySimulation extends BookingProofSimulation {

    private static final String SCENARIO = "HOT_SEAT_CONCURRENCY";
    private static final String SELECT_REJECTION_CODE = "E4001";
    private static final Set<String> ORDER_REJECTION_CODES = Set.of("E5000", "E5001", "E6000", "E6003");

    public HotSeatConcurrencySimulation() {
        super(SCENARIO);
        final int users = LoadTestConfig.users();
        if (users < 2) {
            throw new IllegalArgumentException("-Dusers must be at least 2 for hot-seat contention");
        }

        final HttpProtocolBuilder httpProtocol = http
                .baseUrl(LoadTestConfig.coreBaseUrl())
                .shareConnections()
                .acceptHeader("application/json")
                .contentTypeHeader("application/json");

        final ScenarioBuilder scenario = scenario("02-hot-seat-concurrency")
                .feed(LoadTestConfig.bookingFeeder(users))
                .exec(CoreBookingFlow.initializeSession(SCENARIO))
                .rendezVous(users)
                .exec(recordCoreAdmission())
                .exec(selectSeat())
                .exec(classifySelect())
                .exec(doIf(session -> session.getBoolean("selectWon")).then(
                        dummy("select won", 0)
                ))
                .exec(doIf(session -> session.getBoolean("selectRejected")).then(
                        dummy("select business rejected", 0)
                ))
                .exec(doIf(session -> session.getBoolean("selectTechnicalFailure")).then(
                        dummy("select technical failure", 0)
                                .withSuccess(false)
                                .withSessionUpdate(Session::markAsFailed)
                ))
                .exec(doIf(HotSeatConcurrencySimulation::canContinue).then(
                        exec(createOrder())
                                .exec(classifyOrder())
                ))
                .exec(doIf(session -> session.getBoolean("orderTechnicalFailure")).then(
                        dummy("order technical failure", 0)
                                .withSuccess(false)
                                .withSessionUpdate(Session::markAsFailed)
                ))
                .exec(doIf(session -> session.getBoolean("orderWithoutSelect")).then(
                        dummy("order without select", 0)
                                .withSuccess(false)
                                .withSessionUpdate(Session::markAsFailed)
                ))
                .exec(doIf(session -> session.getBoolean("orderBusinessRejected")).then(
                        dummy("order business rejected", 0)
                ))
                .exec(doIf(session -> session.getBoolean("orderWon") && canContinue(session)).then(
                        resolveOrderKey()
                ))
                .exec(doIf(session -> session.getBoolean("orderWon") && canContinue(session)).then(
                        dummy("order won", 0)
                ))
                .exec(recordTerminal());

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().count().is(0L),
                        details("select won").successfulRequests().count().is(1L),
                        details("select business rejected").successfulRequests().count().is(users - 1L),
                        details("order won").successfulRequests().count().is(1L),
                        details("order business rejected").successfulRequests().count().is(users - 1L),
                        details("select seat").responseTime().percentile(99.0)
                                .lt(LoadTestConfig.coreP99ThresholdMs()),
                        details("create order").responseTime().percentile(99.0)
                                .lt(LoadTestConfig.coreP99ThresholdMs())
                );
    }

    private ChainBuilder recordCoreAdmission() {
        return exec(session -> {
            final Instant admittedAt = BookingEvidenceRecorder.recordCoreAdmission(
                    Path.of(LoadTestConfig.resultFile()),
                    SCENARIO,
                    LoadTestConfig.nodeIndex()
            );
            return session.set("coreAdmittedAt", admittedAt.toString()).set("lastStep", "CORE_ADMITTED");
        });
    }

    private ChainBuilder selectSeat() {
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

    private ChainBuilder classifySelect() {
        return exec(session -> {
            final int httpStatus = optionalInt(session, "selectHttpStatus");
            final String errorCode = optionalString(session, "selectErrorCode");
            final boolean won = httpStatus == 200;
            final boolean rejected = httpStatus == 409 && SELECT_REJECTION_CODE.equals(errorCode);
            Session updated = session
                    .set("selectWon", won)
                    .set("selectRejected", rejected)
                    .set("selectTechnicalFailure", !won && !rejected)
                    .set("orderWon", false)
                    .set("orderBusinessRejected", false)
                    .set("orderWithoutSelect", false)
                    .set("orderTechnicalFailure", false);
            if (!won && !rejected) {
                updated = terminalFailure(updated, technicalResult("SELECT_SEAT", httpStatus),
                        "SELECT_SEAT", httpStatus);
            }
            return updated;
        });
    }

    private ChainBuilder createOrder() {
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

    private ChainBuilder classifyOrder() {
        return exec(session -> {
            final int httpStatus = optionalInt(session, "createOrderHttpStatus");
            final String errorCode = optionalString(session, "orderErrorCode");
            final boolean won = httpStatus == 201;
            final boolean businessRejected = httpStatus == 409 && ORDER_REJECTION_CODES.contains(errorCode);
            final boolean orderWithoutSelect = won && !session.getBoolean("selectWon");
            final boolean technicalFailure = !won && !businessRejected;
            Session updated = session
                    .set("orderWon", won)
                    .set("orderBusinessRejected", businessRejected)
                    .set("orderWithoutSelect", orderWithoutSelect)
                    .set("orderTechnicalFailure", technicalFailure);
            if (orderWithoutSelect) {
                return terminalFailure(updated, "INVARIANT_ORDER_WITHOUT_SELECT", "CREATE_ORDER", httpStatus);
            }
            if (technicalFailure) {
                return terminalFailure(updated, technicalResult("CREATE_ORDER", httpStatus),
                        "CREATE_ORDER", httpStatus);
            }
            if (businessRejected) {
                return updated
                        .set("terminalResult", "BUSINESS_REJECTED_" + errorCode)
                        .set("terminalHttpStatus", httpStatus)
                        .set("lastStep", "CREATE_ORDER");
            }
            return updated;
        });
    }

    private ChainBuilder resolveOrderKey() {
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
            return session
                    .set("orderKey", headerOrderKey.isBlank() ? bodyOrderKey : headerOrderKey)
                    .set("terminalResult", "SUCCESS")
                    .set("terminalHttpStatus", optionalInt(session, "createOrderHttpStatus"))
                    .set("lastStep", "COMPLETED");
        });
    }

    private ChainBuilder recordTerminal() {
        return exec(session -> {
            final String result = optionalString(session, "terminalResult");
            final String normalizedResult = result.isBlank() ? "INVARIANT_MISSING_TERMINAL_RESULT" : result;
            final Session updated = result.isBlank() ? session.markAsFailed() : session;
            BookingResultRecorder.append(
                    Path.of(LoadTestConfig.resultFile()),
                    SCENARIO,
                    LoadTestConfig.nodeIndex(),
                    updated.getLong("memberId"),
                    updated.getLong("seatId"),
                    optionalString(updated, "orderKey"),
                    optionalInt(updated, "terminalHttpStatus"),
                    normalizedResult,
                    optionalString(updated, "lastStep"),
                    optionalString(updated, "flowStartedAt"),
                    optionalString(updated, "coreAdmittedAt")
            );
            return updated;
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

    private static String technicalResult(final String step, final int httpStatus) {
        return httpStatus == 0
                ? "TECHNICAL_" + step + "_NO_RESPONSE"
                : "TECHNICAL_" + step + "_HTTP_" + httpStatus;
    }

    private static int optionalInt(final Session session, final String key) {
        return session.contains(key) ? session.getInt(key) : 0;
    }

    private static String optionalString(final Session session, final String key) {
        return session.contains(key) ? session.getString(key) : "";
    }
}