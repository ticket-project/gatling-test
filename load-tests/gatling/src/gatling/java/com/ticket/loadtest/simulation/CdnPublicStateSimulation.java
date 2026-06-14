package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class CdnPublicStateSimulation extends Simulation {

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(LoadTestConfig.baseUrl())
            .acceptHeader("application/json");

    public CdnPublicStateSimulation() {
        final ScenarioBuilder scenario = scenario("cdn-public-state")
                .exec(LoadTestConfig.initializeSession())
                .repeat(LoadTestConfig.statusPolls()).on(
                        exec(http("cdn public state")
                                .get("/queue-state/performances/#{performanceId}.json")
                                .check(status().is(200))
                                .check(jsonPath("$.performanceId").exists())
                                .check(jsonPath("$.admittedUntilSeq").exists()))
                                .pause(Duration.ofSeconds(LoadTestConfig.statusPollPauseSeconds()))
                );

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        details("cdn public state").successfulRequests().count().gt(0L),
                        details("cdn public state").failedRequests().percent().lt(1.0)
                );
    }
}
