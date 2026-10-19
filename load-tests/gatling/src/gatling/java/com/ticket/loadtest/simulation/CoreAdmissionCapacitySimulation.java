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
                // 주문 생성 응답이 이미 상태(PENDING)·만료 시각을 담고 있어 바로 다시 묻지 않는다(2026-10-19 사용자 결정: 03은 FE 흐름에 맞추지 않는다).
                .exec(CoreBookingFlow.successfulFlowWithoutAdmission(SCENARIO, false));

        run(scenario, LoadTestConfig.coreAdmissionCapacityInjection());
    }
}
