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

public class CoreSeatSelectApiSimulation extends Simulation {

    public CoreSeatSelectApiSimulation() {
        final HttpProtocolBuilder httpProtocol = http
                .baseUrl(LoadTestConfig.coreBaseUrl())
                .shareConnections()
                .acceptHeader("application/json");

        final ScenarioBuilder scenario = scenario("POST /api/v1/performances/{performanceId}/seats/{seatId}/select")
                .feed(LoadTestConfig.bookingFeeder(LoadTestConfig.expectedUsers()))
                .exec(session -> session.set("performanceId", LoadTestConfig.performanceId()))
                .exec(http("select seat")
                        .post("/api/v1/performances/#{performanceId}/seats/#{seatId}/select")
                        .headers(LoadTestConfig.authAndCorrelationHeaders())
                        .check(status().is(200)));

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent()
                                .lt(LoadTestConfig.technicalFailureThresholdPercent()),
                        details("select seat").responseTime().percentile(95.0)
                                .lt(LoadTestConfig.seatSelectP95ThresholdMs()),
                        details("select seat").responseTime().percentile(99.0)
                                .lt(LoadTestConfig.seatSelectP99ThresholdMs())
                );
    }
}
