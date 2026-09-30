package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.scenario;

public class CoreAdmissionCapacitySimulation extends BookingProofSimulation {

    private static final String SCENARIO = "CORE_ADMISSION_CAPACITY";

    public CoreAdmissionCapacitySimulation() {
        super(SCENARIO);
        final HttpProtocolBuilder httpProtocol = Protocols.json(LoadTestConfig.coreBaseUrl());

        final ScenarioBuilder scenario = scenario("03 고정 조건 Core 수용량")
                .feed(LoadTestConfig.bookingFeeder(LoadTestConfig.expectedUsers()))
                .exec(CoreBookingFlow.initializeSession(SCENARIO))
                .exec(CoreBookingFlow.successfulFlowWithoutAdmission(SCENARIO, true));

        setUp(scenario.injectOpen(LoadTestConfig.coreAdmissionCapacityInjection()))
                .protocols(httpProtocol)
                .assertions(
                        Protocols.technicalFailuresBelowThreshold()
                );
    }
}
