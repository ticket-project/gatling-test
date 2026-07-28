package com.ticket.loadtest.simulation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProofSuiteSimulationTest {

    private static final Path SIMULATION_ROOT = Path.of("src/gatling/java/com/ticket/loadtest/simulation");
    private static final Path CONFIG = Path.of("src/gatling/java/com/ticket/loadtest/LoadTestConfig.java");

    @Test
    void usesTheSameRealBookingFlowForSmokeCapacitySpikeAndQueueProtection() throws IOException {
        final String flow = source(SIMULATION_ROOT.resolve("CoreBookingFlow.java"));

        assertFalse(flow.contains("/api/v1/members"));
        assertTrue(flow.contains("/performances/#{performanceId}/seats/status"));
        assertTrue(flow.contains("/performances/#{performanceId}/seats/#{seatId}/select"));
        assertTrue(flow.contains(".post(\"/api/v1/orders\")"));
        assertTrue(flow.contains("BookingResultRecorder.append("));

        assertTrue(source(SIMULATION_ROOT.resolve("SmokeSimulation.java"))
                .contains("CoreBookingFlow.successfulFlow(SCENARIO, true)"));
        assertTrue(source(SIMULATION_ROOT.resolve("CoreAdmissionCapacitySimulation.java"))
                .contains("CoreBookingFlow.successfulFlow(SCENARIO, false)"));
        assertTrue(source(SIMULATION_ROOT.resolve("CoreActiveUsersClosedSimulation.java"))
                .contains("CoreBookingFlow.successfulFlow(SCENARIO, false)"));
        assertTrue(source(SIMULATION_ROOT.resolve("CoreSpikeSimulation.java"))
                .contains("CoreBookingFlow.successfulFlow(SCENARIO, false)"));
        assertTrue(source(SIMULATION_ROOT.resolve("QueueProtectsCoreSimulation.java"))
                .contains("CoreBookingFlow.successfulFlow(SCENARIO, false)"));
    }

    @Test
    void hotSeatRequiresExactlyOneSelectionAndOrderWinner() throws IOException {
        final String source = source(SIMULATION_ROOT.resolve("HotSeatConcurrencySimulation.java"));

        assertTrue(source.contains(".rendezVous(users)"));
        assertTrue(source.contains("details(\"select won\").successfulRequests().count().is(1L)"));
        assertTrue(source.contains("details(\"order won\").successfulRequests().count().is(1L)"));
        assertTrue(source.contains("details(\"select business rejected\").successfulRequests().count().is(users - 1L)"));
        assertTrue(source.contains("details(\"order business rejected\").successfulRequests().count().is(users - 1L)"));
        assertTrue(source.contains("final boolean orderWithoutSelect = won && !session.getBoolean(\"selectWon\")"));
    }

    @Test
    void coreSpikeUsesThirtySecondBaselinesAndFiveSecondRamps() throws IOException {
        final String config = source(CONFIG);
        final String simulation = source(SIMULATION_ROOT.resolve("CoreSpikeSimulation.java"));

        assertTrue(config.contains("CORE_SPIKE_BASELINE_SECONDS = 30"));
        assertTrue(config.contains("CORE_SPIKE_RAMP_SECONDS = 5"));
        assertTrue(config.contains("CORE_SPIKE_RECOVERY_SECONDS = 30"));
        assertTrue(config.contains("rampUsersPerSec(baselineUsersPerSecond).to(peakUsersPerSecond)"));
        assertTrue(simulation.contains("LoadTestConfig.coreSpikeInjection()"));
        assertTrue(simulation.contains("LoadTestConfig.coreSpikeExpectedUsers()"));
    }

    @Test
    void coreActiveUsersUsesAClosedModelWithRampAndSteadyState() throws IOException {
        final String config = source(CONFIG);
        final String simulation = source(SIMULATION_ROOT.resolve("CoreActiveUsersClosedSimulation.java"));

        assertTrue(config.contains("CORE_ACTIVE_USERS_RAMP_SECONDS = 30"));
        assertTrue(config.contains("rampConcurrentUsers(0).to(users())"));
        assertTrue(config.contains("constantConcurrentUsers(users())"));
        assertTrue(config.contains("rows < users()"));
        assertTrue(simulation.contains("injectClosed(LoadTestConfig.coreActiveUsersInjection())"));
        assertTrue(simulation.contains("scenario(\"04-core-active-users-closed\")"));
        assertTrue(simulation.contains("bookingFeeder(LoadTestConfig.bookingFeederRows())"));
        assertTrue(simulation.contains("dummy(\"core flow completed\", 0)"));
    }

    @Test
    void queueProtectionSeparatesExternalArrivalsFromCoreAdmissions() throws IOException {
        final String source = source(SIMULATION_ROOT.resolve("QueueProtectsCoreSimulation.java"));

        assertTrue(source.contains("dummy(\"external arrival\", 0)"));

        assertTrue(source.contains("jsonPath(\"$.serving['#{shardId}']\")"));
        assertTrue(source.contains("jsonPath(\"$.admissionToken\").optional().saveAs(\"admissionToken\")"));
        assertTrue(source.contains("CoreBookingFlow.successfulFlow(SCENARIO, false)"));
        assertTrue(source.indexOf("dummy(\"external arrival\", 0)")
                < source.indexOf("CoreBookingFlow.successfulFlow(SCENARIO, false)"));
    }

    private static String source(final Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
