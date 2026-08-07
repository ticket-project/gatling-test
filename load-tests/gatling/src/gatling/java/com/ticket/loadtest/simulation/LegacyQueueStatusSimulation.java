package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.bodyString;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class LegacyQueueStatusSimulation extends Simulation {

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(LoadTestConfig.baseUrl())
            .shareConnections()
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    public LegacyQueueStatusSimulation() {
        HttpRequestActionBuilder queueStatus = http("queue status")
                .get("/api/v1/queue/performances/#{performanceId}/status")
                .headers(LoadTestConfig.queueSessionHeaders());

        if (LoadTestConfig.dumpFailureBodyEnabled()) {
            queueStatus = queueStatus
                    .check(status().saveAs("httpStatus"))
                    .check(header("Server").optional().saveAs("responseServer"))
                    .check(header("CF-Ray").optional().saveAs("responseCfRay"))
                    .check(header("CF-Cache-Status").optional().saveAs("responseCfCacheStatus"))
                    .check(bodyString().optional().saveAs("responseBody"));
        }

        queueStatus = queueStatus.check(status().is(200));

        ChainBuilder queueStatusChain = exec(queueStatus);
        if (LoadTestConfig.dumpFailureBodyEnabled()) {
            queueStatusChain = queueStatusChain.exec(LoadTestConfig.dumpFailureResponseBody("queue-status"));
        }

        final ScenarioBuilder scenario = scenario("기존 대기열 상태 조회")
                .exec(LoadTestConfig.initializeSession())
                .feed(legacyQueueSessionFeeder())
                .repeat(LoadTestConfig.statusPolls()).on(
                        queueStatusChain
                                .pause(LoadTestConfig.statusPollPauseMin(), LoadTestConfig.statusPollPauseMax())
                );

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        details("queue status").successfulRequests().count().gt(0L),
                        details("queue status").failedRequests().percent().lt(1.0)
                );
    }

    private Iterator<Map<String, Object>> legacyQueueSessionFeeder() {
        final AtomicInteger counter = new AtomicInteger();
        final int sessionCount = Math.max(1, LoadTestConfig.users());
        return Stream.generate(() -> {
            final int sessionIndex = Math.floorMod(counter.getAndIncrement(), sessionCount);
            return Map.<String, Object>of(
                    "queueSessionId", "bench-" + LoadTestConfig.performanceId() + "-" + sessionIndex
            );
        }).iterator();
    }
}
