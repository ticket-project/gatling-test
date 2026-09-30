package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.scenario;

public class CoreSeatSelectApiSimulation extends Simulation {

    public CoreSeatSelectApiSimulation() {
        final HttpProtocolBuilder httpProtocol = Protocols.acceptJson(LoadTestConfig.coreBaseUrl());

        final ScenarioBuilder scenario = scenario("POST /api/v1/performances/{performanceId}/seats/{seatId}/select")
                .feed(LoadTestConfig.bookingFeeder(LoadTestConfig.expectedUsers()))
                .exec(session -> session.set("performanceId", LoadTestConfig.performanceId()))
                .exec(CoreBookingFlow.selectSeat(false, false));

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        Protocols.technicalFailuresBelowThreshold()
                );
    }
}
