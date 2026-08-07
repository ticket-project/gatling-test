package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class CorePerformanceSummaryApiSimulation extends Simulation {

    public CorePerformanceSummaryApiSimulation() {
        final HttpProtocolBuilder httpProtocol = http
                .baseUrl(LoadTestConfig.coreBaseUrl())
                .shareConnections()
                .acceptHeader("application/json");

        final ScenarioBuilder scenario = scenario("GET /api/v1/performances/{performanceId}/summary")
                .exec(LoadTestConfig.initializeSession())
                .exec(http("performance summary")
                        .get("/api/v1/performances/#{performanceId}/summary")
                        .headers(LoadTestConfig.loadTestCorrelationHeaders())
                        .check(status().is(200)));

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent()
                                .lt(LoadTestConfig.technicalFailureThresholdPercent()),
                        details("performance summary").responseTime().percentile(95.0)
                                .lt(LoadTestConfig.performanceSummaryP95ThresholdMs()),
                        details("performance summary").responseTime().percentile(99.0)
                                .lt(LoadTestConfig.performanceSummaryP99ThresholdMs())
                );
    }
}
