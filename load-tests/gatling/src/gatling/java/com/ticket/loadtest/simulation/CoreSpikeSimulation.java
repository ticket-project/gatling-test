package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;

import static io.gatling.javaapi.core.CoreDsl.scenario;

public class CoreSpikeSimulation extends BookingProofSimulation {

    private static final String SCENARIO = "CORE_SPIKE";

    public CoreSpikeSimulation() {
        super(SCENARIO);
        final ScenarioBuilder scenario = scenario("05 Core 순간 부하 및 회복")
                .feed(LoadTestConfig.bookingFeeder(LoadTestConfig.coreSpikeExpectedUsers()))
                .exec(CoreBookingFlow.initializeRealisticSession(SCENARIO))
                .exec(CoreBookingFlow.realisticFlow(SCENARIO));

        run(scenario, LoadTestConfig.coreSpikeInjection());
    }
}
