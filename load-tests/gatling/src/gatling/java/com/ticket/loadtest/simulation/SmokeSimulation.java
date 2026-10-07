package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;

public class SmokeSimulation extends BookingProofSimulation {

    private static final String SCENARIO = "SMOKE";

    public SmokeSimulation() {
        super(SCENARIO);
        final HttpProtocolBuilder httpProtocol = Protocols.json(LoadTestConfig.coreBaseUrl());

        final ScenarioBuilder scenario = scenario("01 기본 예매 동작 확인")
                .feed(LoadTestConfig.bookingFeeder(LoadTestConfig.expectedUsers()))
                .exec(CoreBookingFlow.initializeSession(SCENARIO))
                .exec(CoreBookingFlow.successfulFlow(SCENARIO, true));

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().count().is(0L)
                );
    }
}
