package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.bodyString;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class QueueJoinOnlySimulation extends Simulation {

    private final HttpProtocolBuilder httpProtocol = buildHttpProtocol();

    public QueueJoinOnlySimulation() {
        final ScenarioBuilder scenario = buildScenario("queue-join-only", "queue join", "queue-join");
        if (LoadTestConfig.ticketOpenEnabled()) {
            final ScenarioBuilder recoveryScenario =
                    buildScenario("queue-join-recovery", "queue join recovery", "queue-join-recovery");
            setUp(
                    scenario.injectOpen(LoadTestConfig.injection()),
                    recoveryScenario.injectOpen(LoadTestConfig.ticketOpenRecoveryInjection())
            )
                    .protocols(httpProtocol)
                    .assertions(
                            global().failedRequests().percent().lt(1.0),
                            details("queue join").responseTime().percentile(99.0).lt(2000),
                            details("queue join recovery").failedRequests().percent().lt(1.0),
                            details("queue join recovery").responseTime().percentile(99.0).lt(2000)
                    );
            return;
        }

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        details("queue join").responseTime().percentile(99.0).lt(2000)
                );
    }

    private ScenarioBuilder buildScenario(
            final String scenarioName,
            final String requestName,
            final String failureDumpName
    ) {
        ScenarioBuilder scenario = scenario(scenarioName)
                .exec(LoadTestConfig.initializeSession())
                .exec(LoadTestConfig.authenticate())
                .exec(buildQueueJoin(requestName));
        if (LoadTestConfig.dumpFailureBodyEnabled()) {
            scenario = scenario.exec(LoadTestConfig.dumpFailureResponseBody(failureDumpName));
        }
        return scenario;
    }

    private HttpRequestActionBuilder buildQueueJoin(final String requestName) {
        HttpRequestActionBuilder queueJoin = http(requestName)
                .post(LoadTestConfig.queueBaseUrl() + "/api/v1/queue/performances/#{performanceId}/join")
                .headers(LoadTestConfig.authHeaders());
        if (LoadTestConfig.dumpFailureBodyEnabled()) {
            queueJoin = queueJoin
                    .check(status().saveAs("httpStatus"))
                    .check(header("Server").optional().saveAs("responseServer"))
                    .check(header("CF-Ray").optional().saveAs("responseCfRay"))
                    .check(header("CF-Cache-Status").optional().saveAs("responseCfCacheStatus"))
                    .check(bodyString().optional().saveAs("responseBody"));
        }
        return queueJoin
                .check(status().is(200))
                .check(jsonPath("$.queueToken").saveAs("queueToken"))
                .check(jsonPath("$.seq").ofLong().optional().saveAs("queueSeq"))
                .check(jsonPath("$.shardId").ofInt().saveAs("queueShardId"))
                .check(jsonPath("$.localSeq").ofLong().saveAs("queueLocalSeq"))
                .check(jsonPath("$.pollAfterMs").ofLong().saveAs("queuePollAfterMs"));
    }

    private static HttpProtocolBuilder buildHttpProtocol() {
        HttpProtocolBuilder protocol = http
                .baseUrl(LoadTestConfig.baseUrl())
                .shareConnections()
                .acceptHeader("application/json")
                .contentTypeHeader("application/json");
        return LoadTestConfig.http2Enabled() ? protocol.enableHttp2() : protocol;
    }
}
