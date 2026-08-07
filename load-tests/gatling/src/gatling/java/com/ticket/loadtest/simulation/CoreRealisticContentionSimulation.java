package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

public class CoreRealisticContentionSimulation extends BookingProofSimulation {

    private static final String SCENARIO = "CORE_REALISTIC_CONTENTION";

    public CoreRealisticContentionSimulation() {
        super(SCENARIO);
        final HttpProtocolBuilder httpProtocol = http
                .baseUrl(LoadTestConfig.coreBaseUrl())
                .shareConnections()
                .acceptHeader("application/json")
                .contentTypeHeader("application/json");

        final ScenarioBuilder scenario = scenario("03-2 현실형 인기 좌석 경합")
                .exec(LoadTestConfig.initializeSession())
                .exec(LoadTestConfig.authenticate())
                .exec(CoreBookingFlow.initializeRealisticSession(SCENARIO))
                .exec(CoreBookingFlow.realisticFlowWithoutAdmission(SCENARIO));

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent()
                                .lt(LoadTestConfig.technicalFailureThresholdPercent()),
                        details("performance summary").responseTime().percentile(95.0)
                                .lt(LoadTestConfig.performanceSummaryP95ThresholdMs()),
                        details("performance summary").responseTime().percentile(99.0)
                                .lt(LoadTestConfig.performanceSummaryP99ThresholdMs()),
                        details("seat status").responseTime().percentile(95.0)
                                .lt(LoadTestConfig.seatStatusP95ThresholdMs()),
                        details("seat status").responseTime().percentile(99.0)
                                .lt(LoadTestConfig.seatStatusP99ThresholdMs()),
                        details("select seat").responseTime().percentile(95.0)
                                .lt(LoadTestConfig.seatSelectP95ThresholdMs()),
                        details("select seat").responseTime().percentile(99.0)
                                .lt(LoadTestConfig.seatSelectP99ThresholdMs()),
                        details("create order").responseTime().percentile(95.0)
                                .lt(LoadTestConfig.orderCreateP95ThresholdMs()),
                        details("create order").responseTime().percentile(99.0)
                                .lt(LoadTestConfig.orderCreateP99ThresholdMs()),
                        details("get order").responseTime().percentile(95.0)
                                .lt(LoadTestConfig.orderGetP95ThresholdMs()),
                        details("get order").responseTime().percentile(99.0)
                                .lt(LoadTestConfig.orderGetP99ThresholdMs())
                );
    }
}
