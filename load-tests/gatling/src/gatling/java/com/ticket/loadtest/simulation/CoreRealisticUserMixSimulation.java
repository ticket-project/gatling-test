package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

/**
 * 03-3 실제 사용자 흐름 혼합. 03-2와 같은 좌석 경합 모델에 좌석 알림 WebSocket, 배치도 조회, 이탈 시 전체 해제, 결제 전 주문 취소를 더한다.
 *
 * <p>03-2와 결과를 섞지 않도록 별도 시나리오로 둔다. 새 행동의 비율은 {@code bookingDropoutPercent}와 {@code bookingOrderCancelPercent}로 바꾼다.
 */
public class CoreRealisticUserMixSimulation extends BookingProofSimulation {

    private static final String SCENARIO = "CORE_REALISTIC_USER_MIX";

    public CoreRealisticUserMixSimulation() {
        super(SCENARIO);
        final HttpProtocolBuilder httpProtocol = http
                .baseUrl(LoadTestConfig.coreBaseUrl())
                .wsBaseUrl(LoadTestConfig.coreWsBaseUrl())
                .shareConnections()
                .acceptHeader("application/json")
                .contentTypeHeader("application/json");

        final ScenarioBuilder scenario = scenario("03-3 실제 사용자 흐름 혼합")
                .exec(LoadTestConfig.initializeSession())
                .exec(LoadTestConfig.authenticate())
                .exec(CoreBookingFlow.initializeRealisticSession(SCENARIO))
                .exec(CoreBookingFlow.realisticUserMixFlow(SCENARIO));

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent()
                                .lt(LoadTestConfig.technicalFailureThresholdPercent())
                );
    }
}
