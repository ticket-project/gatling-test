package com.ticket.loadtest.simulation;

import com.ticket.loadtest.CdnCacheCounters;
import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.bodyString;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class CdnPublicStateSimulation extends Simulation {

    private static final CdnCacheCounters CDN_CACHE_COUNTERS = new CdnCacheCounters();

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(LoadTestConfig.baseUrl())
            .shareConnections()
            .acceptHeader("application/json");

    public CdnPublicStateSimulation() {
        HttpRequestActionBuilder publicState = http("cdn public state")
                .get("/api/v1/queue/performances/#{performanceId}/state");

        if (LoadTestConfig.dumpFailureBodyEnabled()) {
            publicState = publicState
                    .check(status().saveAs("httpStatus"))
                    .check(header("Server").optional().saveAs("responseServer"))
                    .check(header("CF-Ray").optional().saveAs("responseCfRay"))
                    .check(header("CF-Cache-Status").optional().saveAs("responseCfCacheStatus"))
                    .check(bodyString().optional().saveAs("responseBody"));
        }

        publicState = publicState
                .check(status().is(200))
                .check(header("X-Cache").optional().saveAs("cdnXCache"))
                .check(header("CF-Cache-Status").optional().saveAs("cdnCfCacheStatus"))
                .check(header("Cache-Status").optional().saveAs("cdnCacheStatus"))
                .check(header("Age").optional().saveAs("cdnAge"))
                .check(jsonPath("$.performanceId").exists())
                .check(jsonPath("$.admittedUntilSeq").exists());

        ChainBuilder publicStateChain = exec(publicState)
                .exec(session -> {
                    CDN_CACHE_COUNTERS.record(
                            headerValue(session, "cdnXCache"),
                            headerValue(session, "cdnCfCacheStatus"),
                            headerValue(session, "cdnCacheStatus"),
                            headerValue(session, "cdnAge")
                    );
                    return session.remove("cdnXCache")
                            .remove("cdnCfCacheStatus")
                            .remove("cdnCacheStatus")
                            .remove("cdnAge");
                });
        if (LoadTestConfig.dumpFailureBodyEnabled()) {
            publicStateChain = publicStateChain.exec(LoadTestConfig.dumpFailureResponseBody("cdn-public-state"));
        }

        final ScenarioBuilder scenario = scenario("CDN 공개 대기열 상태 조회")
                .exec(LoadTestConfig.initializeSession())
                .repeat(LoadTestConfig.statusPolls()).on(
                        publicStateChain
                                .pause(LoadTestConfig.statusPollPauseMin(), LoadTestConfig.statusPollPauseMax())
                );

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        details("cdn public state").successfulRequests().count().gt(0L),
                        details("cdn public state").failedRequests().percent().lt(1.0)
                );

    }

    @Override
    public void after() {
        CDN_CACHE_COUNTERS.printSummary();
        CDN_CACHE_COUNTERS.writeSummary(CdnCacheCounters.defaultSummaryPath("cdnpublicstatesimulation-"));
    }

    private static String headerValue(final Session session, final String key) {
        return session.contains(key) ? session.getString(key) : null;
    }
}
