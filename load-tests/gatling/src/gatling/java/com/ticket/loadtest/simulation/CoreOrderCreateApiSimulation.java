package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import com.ticket.loadtest.OrderLookupFeeder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.nio.file.Path;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.status;

public class CoreOrderCreateApiSimulation extends Simulation {

    public CoreOrderCreateApiSimulation() {
        final Path orderLookupFeeder = Path.of(LoadTestConfig.resultFile());
        OrderLookupFeeder.initialize(orderLookupFeeder);
        final HttpProtocolBuilder httpProtocol = http
                .baseUrl(LoadTestConfig.coreBaseUrl())
                .shareConnections()
                .acceptHeader("application/json")
                .contentTypeHeader("application/json");

        final ScenarioBuilder scenario = scenario("POST /api/v1/orders")
                .feed(LoadTestConfig.bookingFeeder(LoadTestConfig.expectedUsers()))
                .exec(session -> session
                        .set("performanceId", LoadTestConfig.performanceId())
                        .set("seatIdsJson", "[" + session.getLong("seatId") + "]"))
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
                        global().failedRequests().percent()
                                .lt(LoadTestConfig.technicalFailureThresholdPercent())
                );
    }
}
