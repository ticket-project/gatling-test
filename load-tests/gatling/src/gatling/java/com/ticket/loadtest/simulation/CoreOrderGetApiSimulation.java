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

public class CoreOrderGetApiSimulation extends Simulation {

    public CoreOrderGetApiSimulation() {
        final HttpProtocolBuilder httpProtocol = http
                .baseUrl(LoadTestConfig.coreBaseUrl())
                .shareConnections()
                .acceptHeader("application/json");

        final ScenarioBuilder scenario = scenario("GET /api/v1/orders/{orderKey}")
                .feed(LoadTestConfig.orderLookupFeeder(LoadTestConfig.expectedUsers()))
                .exec(http("get order")
                        .get("/api/v1/orders/#{orderKey}")
                        .headers(LoadTestConfig.authAndCorrelationHeaders())
                        .check(status().is(200)));

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent()
                                .lt(LoadTestConfig.technicalFailureThresholdPercent()),
                        details("get order").responseTime().percentile(95.0)
                                .lt(LoadTestConfig.orderGetP95ThresholdMs()),
                        details("get order").responseTime().percentile(99.0)
                                .lt(LoadTestConfig.orderGetP99ThresholdMs())
                );
    }
}
