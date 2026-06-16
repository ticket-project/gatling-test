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
    void keepsStatusPollingPauseFixed() throws IOException {
        final String source = Files.readString(SIMULATION_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("scenario(\"ticket-open-flow\")"));
        assertTrue(source.contains(".pause(Duration.ofSeconds(LoadTestConfig.statusPollPauseSeconds()))"));
        assertFalse(source.contains("statusPollPauseMin()"));
        assertFalse(source.contains("statusPollPauseMax()"));
    }
}
