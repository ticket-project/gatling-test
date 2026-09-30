package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.http.HttpDsl.http;

/** 시뮬레이션마다 반복하던 HTTP 프로토콜과 기술 실패 assertion. */
final class Protocols {
    private Protocols() {
    }

    /** 요청 본문을 보내는 시뮬레이션용. Accept와 Content-Type을 모두 JSON으로 둔다. */
    static HttpProtocolBuilder json(final String baseUrl) {
        return acceptJson(baseUrl).contentTypeHeader("application/json");
    }

    static HttpProtocolBuilder acceptJson(final String baseUrl) {
        return http.baseUrl(baseUrl).shareConnections().acceptHeader("application/json");
    }

    static Assertion technicalFailuresBelowThreshold() {
        return global().failedRequests().percent().lt(LoadTestConfig.technicalFailureThresholdPercent());
    }
}
