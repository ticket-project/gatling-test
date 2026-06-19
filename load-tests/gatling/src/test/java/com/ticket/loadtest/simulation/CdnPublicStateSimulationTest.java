package com.ticket.loadtest.simulation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdnPublicStateSimulationTest {

    private static final Path SIMULATION_SOURCE = Path.of(
            "src/gatling/java/com/ticket/loadtest/simulation/CdnPublicStateSimulation.java"
    );

    @Test
    void pollsQueueServerPublicStateThroughCachedOriginWithoutAuthentication() throws IOException {
        final String source = Files.readString(SIMULATION_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("scenario(\"cdn-public-state\")"));
        assertTrue(source.contains(".get(\"/api/v1/queue/performances/#{performanceId}/state\")"));
        assertTrue(source.contains(".check(status().is(200))"));
        assertTrue(source.contains(".check(header(\"X-Cache\").optional().saveAs(\"cdnXCache\"))"));
        assertTrue(source.contains(".check(header(\"CF-Cache-Status\").optional().saveAs(\"cdnCfCacheStatus\"))"));
        assertTrue(source.contains(".check(header(\"Cache-Status\").optional().saveAs(\"cdnCacheStatus\"))"));
        assertTrue(source.contains(".check(header(\"Age\").optional().saveAs(\"cdnAge\"))"));
        assertTrue(source.contains("CDN_CACHE_COUNTERS.record("));
        assertTrue(source.contains("public void after()"));
        assertTrue(source.contains("CDN_CACHE_COUNTERS.printSummary();"));
        assertTrue(source.contains("CDN_CACHE_COUNTERS.writeSummary(CdnCacheCounters.defaultSummaryPath(\"cdnpublicstatesimulation-\"));"));
        assertTrue(source.contains(".check(jsonPath(\"$.performanceId\").exists())"));
        assertTrue(source.contains(".check(jsonPath(\"$.admittedUntilSeq\").exists())"));
        assertTrue(source.contains(".pause(LoadTestConfig.statusPollPauseMin(), LoadTestConfig.statusPollPauseMax())"));
        assertFalse(source.contains(".pause(Duration.ofSeconds(LoadTestConfig.statusPollPauseSeconds()))"));
        assertFalse(source.contains("LoadTestConfig.authenticate()"));
        assertFalse(source.contains("LoadTestConfig.authHeaders()"));
        assertFalse(source.contains("LoadTestConfig.queueSessionHeaders()"));
    }
}
