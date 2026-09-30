package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import com.ticket.loadtest.OrderLookupFeeder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.nio.file.Path;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.status;

public class CoreOrderCreateApiSimulation extends Simulation {

    public CoreOrderCreateApiSimulation() {
        final Path orderLookupFeeder = Path.of(LoadTestConfig.resultFile());
        OrderLookupFeeder.initialize(orderLookupFeeder);
        final HttpProtocolBuilder httpProtocol = Protocols.json(LoadTestConfig.coreBaseUrl());

        final ScenarioBuilder scenario = scenario("POST /api/v1/orders")
                .feed(LoadTestConfig.bookingFeeder(LoadTestConfig.expectedUsers()))
                .exec(session -> session
                        .set("performanceId", LoadTestConfig.performanceId())
                        .set("seatIdsJson", "[" + session.getLong("seatId") + "]"))
                // Core는 본인이 선택 중인 좌석으로만 주문을 받는다(ticket-core ADR 0021). 선택 요청도 함께 측정된다 —
                // 주문 API만 보려면 "create order" 요청 통계를 본다.
                .exec(CoreBookingFlow.selectSeat(false, false))
                .exitHereIfFailed()
                .exec(http("create order")
                        .post("/api/v1/orders")
                        .headers(LoadTestConfig.authAndCorrelationHeaders())
                        .body(StringBody("""
                                {
                                  "performanceId": #{performanceId},
                                  "seatIds": #{seatIdsJson}
                                }
                                """))
                        .check(status().is(201))
                        .check(header("X-Order-Key").saveAs("orderKey")))
                .exec(session -> {
                    if (session.contains("orderKey")) {
                        OrderLookupFeeder.append(
                                orderLookupFeeder,
                                session.getLong("memberId"),
                                session.getString("orderKey")
                        );
                    }
                    return session;
                });

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        Protocols.technicalFailuresBelowThreshold()
                );
    }
}
