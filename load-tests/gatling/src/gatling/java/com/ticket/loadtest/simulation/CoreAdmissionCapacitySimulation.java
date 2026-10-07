package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;

import static io.gatling.javaapi.core.CoreDsl.scenario;

public class CoreAdmissionCapacitySimulation extends BookingProofSimulation {

    private static final String SCENARIO = "CORE_ADMISSION_CAPACITY";

    public CoreAdmissionCapacitySimulation() {
        super(SCENARIO);
        final ScenarioBuilder scenario = scenario("03 고정 조건 Core 수용량")
                .feed(LoadTestConfig.bookingFeeder(LoadTestConfig.expectedUsers()))
                .exec(CoreBookingFlow.initializeSession(SCENARIO))
                .exec(CoreBookingFlow.successfulFlowWithoutAdmission(SCENARIO, true));

        run(scenario, LoadTestConfig.coreAdmissionCapacityInjection());
    }
}
