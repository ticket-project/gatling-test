package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class LegacyQueueStatusSimulation extends Simulation {

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(LoadTestConfig.baseUrl())
            .shareConnections()
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    public LegacyQueueStatusSimulation() {
        final ScenarioBuilder scenario = scenario("legacy-queue-status")
                .exec(LoadTestConfig.initializeSession())
                .feed(legacyQueueSessionFeeder())
                .repeat(LoadTestConfig.statusPolls()).on(
                        exec(http("queue status")
                                .get("/api/v1/queue/performances/#{performanceId}/status")
                                .headers(LoadTestConfig.queueSessionHeaders())
                                .check(status().is(200)))
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
