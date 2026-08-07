package com.ticket.loadtest.simulation;

import com.ticket.loadtest.BookingResultRecorder;
import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.nio.file.Path;
import java.time.Duration;

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

public class BookingCapacitySimulation extends Simulation {

    private static final String SCENARIO = "BOOKING_CAPACITY";
    private static final Duration ORDER_POLL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ORDER_POLL_PAUSE = Duration.ofMillis(200);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(LoadTestConfig.coreBaseUrl())
            .shareConnections()
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    public BookingCapacitySimulation() {
        final ScenarioBuilder scenario = scenario("고정 좌석 예매 처리량")
                .feed(LoadTestConfig.bookingFeeder())
                .exec(session -> session
                        .set("performanceId", LoadTestConfig.performanceId())
                        .set("seatIdsJson", "[" + session.getLong("seatId") + "]"))
                .exec(fetchSeatStatus())
                .exitHereIfFailed()
                .exec(selectSeat())
                .exitHereIfFailed()
                .exec(createOrder())
                .exitHereIfFailed()
                .exec(resolveOrderKey())
                .exec(doIf(session -> session.getBoolean("orderKeyContractFailure")).then(
                        dummy("order key contract failure", 0)
                                .withSuccess(false)
                                .withSessionUpdate(session -> session.markAsFailed())
                ))
                .exitHereIfFailed()
                .exec(session -> session.set("orderPending", false))
                .asLongAsDuring(session -> !session.getBoolean("orderPending"), ORDER_POLL_TIMEOUT).on(
                        exec(fetchOrder())
                                .exitHereIfFailed()
                                .exec(session -> session.set(
                                        "orderPending",
                                        "PENDING".equals(session.getString("orderStatus"))
                                ))
                                .pause(ORDER_POLL_PAUSE)
                )
                .exec(doIf(session -> !session.getBoolean("orderPending")).then(
                        dummy("order state timeout", 0)
                                .withSuccess(false)
                                .withSessionUpdate(session -> session.markAsFailed())
                ))
                .exitHereIfFailed()
                .exec(recordSuccess());

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        details("seat status").responseTime().percentile(99.0).lt(3000),
                        details("select seat").responseTime().percentile(99.0).lt(3000),
                        details("create order").responseTime().percentile(99.0).lt(3000),
                        details("get order").responseTime().percentile(99.0).lt(3000)
                );
    }

    private ChainBuilder fetchSeatStatus() {
        return exec(http("seat status")
                .get("/api/v1/performances/#{performanceId}/seats/status")
                .headers(LoadTestConfig.authAndAdmissionHeaders())
                .check(status().is(200)));
    }

    private ChainBuilder selectSeat() {
        return exec(http("select seat")
                .post("/api/v1/performances/#{performanceId}/seats/#{seatId}/select")
                .headers(LoadTestConfig.authAndAdmissionHeaders())
                .check(status().is(200)));
    }

    private ChainBuilder createOrder() {
        return exec(http("create order")
                .post("/api/v1/orders")
                .headers(LoadTestConfig.authAndAdmissionHeaders())
                .body(StringBody("""
                        {
                          "performanceId": #{performanceId},
                          "seatIds": #{seatIdsJson}
                        }
                        """))
                .check(status().is(201))
                .check(header("X-Order-Key").optional().saveAs("orderKeyHeader"))
                .check(jsonPath("$.data.orderKey").optional().saveAs("orderKeyBody")));
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

    private ChainBuilder recordSuccess() {
        return exec(session -> {
            BookingResultRecorder.append(
                    Path.of(LoadTestConfig.resultFile()),
                    SCENARIO,
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId"),
                    session.getLong("seatId"),
                    session.getString("orderKey"),
                    session.getInt("orderHttpStatus"),
                    "SUCCESS"
            );
            return session;
        });
    }

    private static String optionalString(final io.gatling.javaapi.core.Session session, final String key) {
        return session.contains(key) ? session.getString(key) : "";
    }
}
