package com.ticket.loadtest.simulation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationConnectionPolicyTest {

    private static final Path SIMULATION_DIR = Path.of("src/gatling/java/com/ticket/loadtest/simulation");

    @Test
    void allSimulationsShareConnections() throws IOException {
        final List<Path> simulationSources;
        try (var sources = Files.list(SIMULATION_DIR)) {
            simulationSources = sources
                    .filter(path -> path.getFileName().toString().endsWith("Simulation.java"))
                    .toList();
        }

        assertFalse(simulationSources.isEmpty(), "simulation sources should exist");
        for (Path sourcePath : simulationSources) {
            final String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            assertTrue(
                    source.contains(".shareConnections()"),
                    sourcePath.getFileName() + " should share Gatling HTTP connections"
            );
        }
    }

    @Test
    void ticketOpenFlowUsesQueueTokenContract() throws IOException {
        final String source = readSimulation("TicketOpenFlowSimulation.java");

        assertTrue(source.contains("queue join"), "full flow should join before enter");
        assertTrue(source.contains(".post(LoadTestConfig.queueBaseUrl() + \"/api/v1/queue/performances/#{performanceId}/join\")"));
        assertTrue(source.contains(".check(jsonPath(\"$.queueToken\").saveAs(\"queueToken\"))"));
        assertTrue(source.contains(".headers(LoadTestConfig.queueTokenHeaders())"));
        assertTrue(source.contains(".get(LoadTestConfig.coreBaseUrl() + \"/api/v1/performances/#{performanceId}/seats/status\")"));
        assertTrue(source.contains(".post(LoadTestConfig.coreBaseUrl() + \"/api/v1/orders\")"));
        assertFalse(source.contains("queueSessionId"), "queue session polling contract must not be used");
        assertFalse(source.contains("X-Queue-Session"), "queue session header must not be used");
        assertFalse(source.contains("/api/v1/queue/performances/#{performanceId}/status"), "queue status polling API must not be used");
    }

    @Test
    void queueEnterSimulationUsesJoinThenQueueTokenEnter() throws IOException {
        final String source = readSimulation("QueueEnterSimulation.java");

        assertTrue(source.contains("queue join"), "queue enter simulation should obtain a queue token first");
        assertTrue(source.contains(".post(LoadTestConfig.queueBaseUrl() + \"/api/v1/queue/performances/#{performanceId}/join\")"));
        assertTrue(source.contains(".check(jsonPath(\"$.queueToken\").saveAs(\"queueToken\"))"));
        assertTrue(source.contains(".headers(LoadTestConfig.queueTokenHeaders())"));
        assertFalse(source.contains("queueSessionId"), "queue session contract must not be used");
        assertFalse(source.contains("X-Queue-Session"), "queue session header must not be used");
    }

    @Test
    void queueJoinOnlySimulationOnlyCallsJoin() throws IOException {
        final String source = readSimulation("QueueJoinOnlySimulation.java");

        assertTrue(source.contains("scenario(\"queue-join-only\")"));
        assertTrue(source.contains("queue join"));
        assertTrue(source.contains(".post(LoadTestConfig.queueBaseUrl() + \"/api/v1/queue/performances/#{performanceId}/join\")"));
        assertTrue(source.contains(".headers(LoadTestConfig.authHeaders())"));
        assertTrue(source.contains(".check(status().is(200))"));
        assertTrue(source.contains(".check(jsonPath(\"$.queueToken\").saveAs(\"queueToken\"))"));
        assertTrue(source.contains(".check(jsonPath(\"$.seq\").ofLong().optional().saveAs(\"queueSeq\"))"));
        assertTrue(source.contains(".check(jsonPath(\"$.localSeq\").ofLong().optional().saveAs(\"queueLocalSeq\"))"));
        assertTrue(source.contains("LoadTestConfig.http2Enabled() ? protocol.enableHttp2() : protocol"));
        assertFalse(source.contains("queue enter"), "join-only simulation must not call enter");
        assertFalse(source.contains("/api/v1/queue/performances/#{performanceId}/enter"), "join-only simulation must not call enter");
        assertFalse(source.contains("queueTokenHeaders"), "join-only simulation must not need queue token headers");
    }

    @Test
    void loadTestConfigSupportsModernAndLegacyQueueHeaders() throws IOException {
        final Path configPath = Path.of("src/gatling/java/com/ticket/loadtest/LoadTestConfig.java");
        final String source = Files.readString(configPath, StandardCharsets.UTF_8);

        assertTrue(source.contains("coreBaseUrl"));
        assertTrue(source.contains("queueBaseUrl"));
        assertTrue(source.contains("queueTokenHeaders"));
        assertTrue(source.contains("X-Queue-Token"));
        assertTrue(source.contains("queueSessionHeaders"));
        assertTrue(source.contains("X-Queue-Session"));
    }

    private String readSimulation(final String fileName) throws IOException {
        final Path sourcePath = SIMULATION_DIR.resolve(fileName);
        if (!Files.exists(sourcePath)) {
            throw new AssertionError("simulation source should exist: " + fileName);
        }
        return Files.readString(sourcePath, StandardCharsets.UTF_8);
    }
}
