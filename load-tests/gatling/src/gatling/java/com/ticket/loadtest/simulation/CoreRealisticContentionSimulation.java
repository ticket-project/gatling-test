package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;

import static io.gatling.javaapi.core.CoreDsl.scenario;

public class CoreRealisticContentionSimulation extends BookingProofSimulation {

    private static final String SCENARIO = "CORE_REALISTIC_CONTENTION";

    public CoreRealisticContentionSimulation() {
        super(SCENARIO);
        final ScenarioBuilder scenario = scenario("03-2 현실형 인기 좌석 경합")
                .exec(LoadTestConfig.initializeSession())
                .exec(LoadTestConfig.authenticate())
                .exec(CoreBookingFlow.initializeRealisticSession(SCENARIO))
                .exec(CoreBookingFlow.realisticFlowWithoutAdmission(SCENARIO));

        run(scenario, LoadTestConfig.injection());
    }
}
