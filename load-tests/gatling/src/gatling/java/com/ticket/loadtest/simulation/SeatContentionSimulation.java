package com.ticket.loadtest.simulation;

import com.ticket.loadtest.BookingResultRecorder;
import com.ticket.loadtest.CoreRejections;
import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.dummy;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class SeatContentionSimulation extends Simulation {

    private static final String SCENARIO = "SEAT_CONTENTION";
    private static final Duration ORDER_POLL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ORDER_POLL_PAUSE = Duration.ofMillis(200);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(LoadTestConfig.coreBaseUrl())
            .shareConnections()
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    public SeatContentionSimulation() {
        final ScenarioBuilder scenario = scenario("동일 좌석 경합")
                .feed(LoadTestConfig.bookingFeeder())
                .exec(session -> session
                        .set("performanceId", LoadTestConfig.performanceId())
                        .set("seatIdsJson", "[" + session.getLong("seatId") + "]"))
                .exec(fetchSeatStatus())
                .exitHereIfFailed()
                .exec(selectSeat())
                .exitHereIfFailed()
                .exec(classifySelectResponse())
                .exec(doIf(session -> session.getBoolean("selectRejected")).then(
                        recordResult(null, "selectHttpStatus", session -> "SELECT_" + CoreRejections.resultName(
                                CoreRejections.Kind.BUSINESS_REJECTED, session.getString("selectErrorCode")))
                ))
                .exec(doIf(session -> session.getBoolean("selectOverloaded")).then(
                        recordResult(null, "selectHttpStatus", session -> CoreRejections.resultName(
                                CoreRejections.Kind.OVERLOADED, session.getString("selectErrorCode")))
                ))
                .exitHereIf(session -> session.getBoolean("selectOverloaded"))
                .exec(doIf(session -> session.getBoolean("selectTechnicalFailure")).then(
                        dummy("select seat technical failure", 0)
                                .withSuccess(false)
                                .withSessionUpdate(session -> session.markAsFailed())
                ))
                .exitHereIfFailed()
                .exec(createOrder())
                .exitHereIfFailed()
                .exec(classifyOrderResponse())
                .exec(doIf(session -> session.getBoolean("orderTechnicalFailure")).then(
                        dummy("create order technical failure", 0)
                                .withSuccess(false)
                                .withSessionUpdate(session -> session.markAsFailed())
                ))
                .exitHereIfFailed()
                .exec(doIf(session -> session.getBoolean("orderBusinessRejected")).then(
                        recordBusinessRejection()
                ))
                .exec(doIf(session -> session.getBoolean("orderOverloaded")).then(
                        recordResult(null, "createOrderHttpStatus", session -> CoreRejections.resultName(
                                CoreRejections.Kind.OVERLOADED, session.getString("orderErrorCode")))
                ))
                .exec(doIf(session -> !session.getBoolean("orderBusinessRejected")
                        && !session.getBoolean("orderOverloaded")).then(
                        completeSuccessfulOrder()
                ));

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0)
                );
    }

    private ChainBuilder fetchSeatStatus() {
        return exec(http("seat status")
                .get("/api/v1/performances/#{performanceId}/seats/status")
                .headers(LoadTestConfig.authAndAdmissionHeaders())
                .check(status().is(200)));
    }

    private ChainBuilder selectSeat() {
        return exec(session -> session.remove("selectErrorCode"))
                .exec(http("select seat")
                        .post("/api/v1/performances/#{performanceId}/seats/#{seatId}/select")
                        .headers(LoadTestConfig.authAndAdmissionHeaders())
                        .check(status().saveAs("selectHttpStatus"))
                        .check(status().in(200, 409))
                        .check(jsonPath("$.error.code").optional().saveAs("selectErrorCode")));
    }

    private ChainBuilder classifySelectResponse() {
        return exec(session -> {
            final int httpStatus = session.getInt("selectHttpStatus");
            final String errorCode = optionalString(session, "selectErrorCode");
            final CoreRejections.Kind rejection = CoreRejections.ofSelect(httpStatus, errorCode);
            final boolean rejected = httpStatus != 200 && rejection == CoreRejections.Kind.BUSINESS_REJECTED;
            final boolean overloaded = httpStatus != 200 && rejection == CoreRejections.Kind.OVERLOADED;
            return session
                    .set("selectRejected", rejected)
                    .set("selectOverloaded", overloaded)
                    .set("selectTechnicalFailure", httpStatus != 200 && !rejected && !overloaded);
        });
    }

    private ChainBuilder createOrder() {
        return exec(session -> session.removeAll("orderKeyHeader", "orderKeyBody", "orderErrorCode"))
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

    private ChainBuilder classifyOrderResponse() {
        return exec(session -> {
            final int httpStatus = session.getInt("createOrderHttpStatus");
            final String errorCode = optionalString(session, "orderErrorCode");
            final CoreRejections.Kind rejection = CoreRejections.ofOrder(httpStatus, errorCode);
            final boolean businessRejected = httpStatus != 201 && rejection == CoreRejections.Kind.BUSINESS_REJECTED;
            final boolean overloaded = httpStatus != 201 && rejection == CoreRejections.Kind.OVERLOADED;
            return session
                    .set("orderBusinessRejected", businessRejected)
                    .set("orderOverloaded", overloaded)
                    .set("orderTechnicalFailure", httpStatus != 201 && !businessRejected && !overloaded);
        });
    }

    private ChainBuilder recordBusinessRejection() {
        return exec(session -> {
            BookingResultRecorder.append(
                    Path.of(LoadTestConfig.resultFile()),
                    SCENARIO,
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId"),
                    session.getLong("seatId"),
                    null,
                    session.getInt("createOrderHttpStatus"),
                    CoreRejections.resultName(CoreRejections.Kind.BUSINESS_REJECTED, session.getString("orderErrorCode"))
            );
            return session;
        });
    }

    private ChainBuilder completeSuccessfulOrder() {
        return exec(resolveOrderKey())
                .exec(doIf(session -> session.getBoolean("orderKeyContractFailure")).then(
                        dummy("order key contract failure", 0)
                                .withSuccess(false)
                                .withSessionUpdate(session -> session.markAsFailed())
                ))
                .exitHereIfFailed()
                .exec(session -> session
                        .set("orderPending", false)
                        .set("orderDeadlineNanos", deadlineAfter(ORDER_POLL_TIMEOUT)))
                .asLongAs(session -> !session.getBoolean("orderPending")
                        && remainingNanos(session, "orderDeadlineNanos") > 0).on(
                        exec(fetchOrder())
                                .exitHereIfFailed()
                                .exec(session -> session.set(
                                        "orderPending",
                                        "PENDING".equals(session.getString("orderStatus"))
                                ))
                                .doIf(session -> !session.getBoolean("orderPending")
                                        && remainingNanos(session, "orderDeadlineNanos") > 0).then(
                                        pause(session -> clampedPause(
                                                ORDER_POLL_PAUSE,
                                                remainingNanos(session, "orderDeadlineNanos")
                                        ))
                                )
                )
                .exec(doIf(session -> !session.getBoolean("orderPending")).then(
                        dummy("order state timeout", 0)
                                .withSuccess(false)
                                .withSessionUpdate(session -> session.markAsFailed())
                ))
                .exitHereIfFailed()
                .exec(recordResult("orderKey", "orderHttpStatus", "SUCCESS"));
    }

    private ChainBuilder resolveOrderKey() {
        return exec(session -> {
            final String headerOrderKey = optionalString(session, "orderKeyHeader");
            final String bodyOrderKey = optionalString(session, "orderKeyBody");
            if (headerOrderKey.isBlank() && bodyOrderKey.isBlank()) {
                return session.set("orderKeyContractFailure", true);
            }
            if (!headerOrderKey.isBlank() && !bodyOrderKey.isBlank() && !headerOrderKey.equals(bodyOrderKey)) {
                return session.set("orderKeyContractFailure", true);
            }
            return session
                    .set("orderKeyContractFailure", false)
                    .set("orderKey", headerOrderKey.isBlank() ? bodyOrderKey : headerOrderKey);
        });
    }

    private ChainBuilder fetchOrder() {
        return exec(http("get order")
                .get("/api/v1/orders/#{orderKey}/status")
                .headers(LoadTestConfig.authAndAdmissionHeaders())
                .check(status().saveAs("orderHttpStatus"))
                .check(status().is(200))
                .check(jsonPath("$.data.status").saveAs("orderStatus")));
    }

    private ChainBuilder recordResult(final String orderKeyName, final String statusName, final String result) {
        return recordResult(orderKeyName, statusName, session -> result);
    }

    private ChainBuilder recordResult(
            final String orderKeyName,
            final String statusName,
            final Function<Session, String> result
    ) {
        return exec(session -> {
            BookingResultRecorder.append(
                    Path.of(LoadTestConfig.resultFile()),
                    SCENARIO,
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId"),
                    session.getLong("seatId"),
                    orderKeyName == null ? null : session.getString(orderKeyName),
                    session.getInt(statusName),
                    result.apply(session)
            );
            return session;
        });
    }

    private static long deadlineAfter(final Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static long remainingNanos(final Session session, final String deadlineKey) {
        return Math.max(0L, session.getLong(deadlineKey) - System.nanoTime());
    }

    private static Duration clampedPause(final Duration requestedPause, final long remainingNanos) {
        return Duration.ofNanos(Math.min(requestedPause.toNanos(), remainingNanos));
    }

    private static String optionalString(final Session session, final String key) {
        return session.contains(key) ? session.getString(key) : "";
    }
}