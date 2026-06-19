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
}
