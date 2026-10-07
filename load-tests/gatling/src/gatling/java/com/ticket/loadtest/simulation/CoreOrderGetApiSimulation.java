package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.nio.file.Path;

import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class CoreOrderGetApiSimulation extends Simulation {

    public CoreOrderGetApiSimulation() {
        final HttpProtocolBuilder httpProtocol = Protocols.acceptJson(LoadTestConfig.coreBaseUrl());

        final ScenarioBuilder scenario = scenario("GET /api/v1/orders/{orderKey}")
                .feed(csv(Path.of(LoadTestConfig.bookingFeederFile()).toAbsolutePath().toString()).queue())
                .exec(session -> session.set(
                        "accessToken",
                        LoadTestConfig.syntheticAccessTokenForMember(Long.parseLong(session.getString("memberId")))
                ))
                .exec(http("get order")
                        .get("/api/v1/orders/#{orderKey}")
                        .headers(LoadTestConfig.authAndCorrelationHeaders())
                        .check(status().is(200)));

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        Protocols.technicalFailuresBelowThreshold()
                );
    }
}
