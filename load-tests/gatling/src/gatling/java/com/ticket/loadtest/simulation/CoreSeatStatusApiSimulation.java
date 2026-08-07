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

public class CoreSeatStatusApiSimulation extends Simulation {

    public CoreSeatStatusApiSimulation() {
        final HttpProtocolBuilder httpProtocol = http
                .baseUrl(LoadTestConfig.coreBaseUrl())
                .shareConnections()
                .acceptHeader("application/json");

        final ScenarioBuilder scenario = scenario("GET /api/v1/performances/{performanceId}/seats/status")
                .exec(LoadTestConfig.initializeSession())
                .exec(LoadTestConfig.authenticate())
                .exec(http("seat status")
                        .get("/api/v1/performances/#{performanceId}/seats/status")
                        .headers(LoadTestConfig.authAndCorrelationHeaders())
                        .check(status().is(200)));

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent()
                                .lt(LoadTestConfig.technicalFailureThresholdPercent()),
                        details("seat status").responseTime().percentile(95.0)
                                .lt(LoadTestConfig.seatStatusP95ThresholdMs()),
                        details("seat status").responseTime().percentile(99.0)
                                .lt(LoadTestConfig.seatStatusP99ThresholdMs())
                );
    }
}
