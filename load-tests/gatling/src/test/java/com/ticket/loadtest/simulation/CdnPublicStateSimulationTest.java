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
    void pollsCdnPublicStateJsonWithoutAuthentication() throws IOException {
        final String source = Files.readString(SIMULATION_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("scenario(\"cdn-public-state\")"));
        assertTrue(source.contains(".get(\"/queue-state/performances/#{performanceId}.json\")"));
        assertTrue(source.contains(".check(status().is(200))"));
        assertTrue(source.contains(".check(jsonPath(\"$.performanceId\").exists())"));
        assertTrue(source.contains(".check(jsonPath(\"$.admittedUntilSeq\").exists())"));
        assertTrue(source.contains(".pause(Duration.ofSeconds(LoadTestConfig.statusPollPauseSeconds()))"));
        assertFalse(source.contains("statusPollPauseMin()"));
        assertFalse(source.contains("statusPollPauseMax()"));
        assertFalse(source.contains("LoadTestConfig.authenticate()"));
        assertFalse(source.contains("LoadTestConfig.authHeaders()"));
        assertFalse(source.contains("LoadTestConfig.queueSessionHeaders()"));
    }
}
