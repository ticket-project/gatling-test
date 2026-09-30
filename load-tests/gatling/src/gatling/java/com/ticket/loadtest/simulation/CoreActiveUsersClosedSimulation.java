package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.scenario;

public class CoreActiveUsersClosedSimulation extends BookingProofSimulation {

    private static final String SCENARIO = "CORE_ACTIVE_USERS_CLOSED";

    public CoreActiveUsersClosedSimulation() {
        super(SCENARIO);
        final HttpProtocolBuilder httpProtocol = Protocols.json(LoadTestConfig.coreBaseUrl());

        final ScenarioBuilder scenario = scenario("04 Core 동시 사용자 한계")
                .feed(LoadTestConfig.bookingFeeder(LoadTestConfig.bookingFeederRows()))
                .exec(CoreBookingFlow.initializeRealisticSession(SCENARIO))
                .exec(CoreBookingFlow.realisticFlow(SCENARIO));

        setUp(scenario.injectClosed(LoadTestConfig.coreActiveUsersInjection()))
                .protocols(httpProtocol)
                .assertions(
                        Protocols.technicalFailuresBelowThreshold()
                );
    }
}
