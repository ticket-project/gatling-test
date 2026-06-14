package com.ticket.loadtest.simulation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyQueueStatusSimulationTest {

    private static final Path SIMULATION_SOURCE = Path.of(
            "src/gatling/java/com/ticket/loadtest/simulation/LegacyQueueStatusSimulation.java"
    );

    @Test
    void pollsLegacyQueueStatusWithQueueSessionHeaderOnly() throws IOException {
        final String source = Files.readString(SIMULATION_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("scenario(\"legacy-queue-status\")"));
        assertTrue(source.contains(".get(\"/api/v1/queue/performances/#{performanceId}/status\")"));
        assertTrue(source.contains(".headers(LoadTestConfig.queueSessionHeaders())"));
        assertTrue(source.contains(".check(status().is(200))"));
        assertTrue(source.contains("legacyQueueSessionFeeder"));
        assertFalse(source.contains("LoadTestConfig.authenticate()"));
        assertFalse(source.contains("LoadTestConfig.authHeaders()"));
    }
}
