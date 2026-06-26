package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class QueueJoinOnlySimulation extends Simulation {

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(LoadTestConfig.baseUrl())
            .shareConnections()
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    public QueueJoinOnlySimulation() {
        final ScenarioBuilder scenario = scenario("queue-join-only")
                .exec(LoadTestConfig.initializeSession())
                .exec(LoadTestConfig.authenticate())
                .exec(http("queue join")
                        .post(LoadTestConfig.queueBaseUrl() + "/api/v1/queue/performances/#{performanceId}/join")
                        .headers(LoadTestConfig.authHeaders())
                        .check(status().is(200))
                        .check(jsonPath("$.seq").ofLong().saveAs("queueSeq"))
                        .check(jsonPath("$.queueToken").saveAs("queueToken")));

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(global().failedRequests().percent().lt(1.0));
    }
}
