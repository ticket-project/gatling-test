package com.ticket.loadtest.simulation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketOpenFlowSimulationTest {

    private static final Path SIMULATION_SOURCE = Path.of(
            "src/gatling/java/com/ticket/loadtest/simulation/TicketOpenFlowSimulation.java"
    );

    @Test
    void jittersStatusPollingPause() throws IOException {
        final String source = Files.readString(SIMULATION_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("scenario(\"ticket-open-flow\")"));
        assertTrue(source.contains(".pause(LoadTestConfig.statusPollPauseMin(), LoadTestConfig.statusPollPauseMax())"));
        assertFalse(source.contains(".pause(Duration.ofSeconds(LoadTestConfig.statusPollPauseSeconds()))"));
    }
}
