package com.ticket.loadtest.simulation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreRealisticUserMixSimulationTest {
    private static final Path SIMULATION_ROOT = Path.of("src/gatling/java/com/ticket/loadtest/simulation");

    @Test
    void addsTheScreenActionsThatRealisticContentionLeavesOut() throws IOException {
        final String simulation = source("CoreRealisticUserMixSimulation.java");
        final String flow = source("CoreBookingFlow.java");

        assertTrue(simulation.contains("CoreBookingFlow.realisticUserMixFlow(SCENARIO)"));
        assertTrue(simulation.contains(".wsBaseUrl(LoadTestConfig.coreWsBaseUrl())"));
        assertTrue(flow.contains("connect(\"/ws/websocket\")"));
        assertTrue(flow.contains("destination:/topic/performance/#{performanceId}/seats"));
        assertTrue(flow.contains("Authorization:Bearer #{accessToken}"));
        assertTrue(flow.contains(".get(\"/api/v1/shows/#{showId}/venue-layout\")"));
        assertTrue(flow.contains(".get(\"/api/v1/shows/#{showId}/seats\")"));
        assertTrue(flow.contains(".delete(\"/api/v1/performances/#{performanceId}/seats/select\")"));
        assertTrue(flow.contains(".delete(\"/api/v1/orders/#{orderKey}\")"));
        assertTrue(flow.contains("\"USER_DROPPED_ORDER_CANCELED\""));
    }

    @Test
    void releasesSelectionsOnlyForUsersWhoLeaveBeforeOrdering() throws IOException {
        final String flow = source("CoreBookingFlow.java");
        final int mix = flow.indexOf("static ChainBuilder realisticUserMixFlow(");
        final int mixEnd = flow.indexOf("private static ChainBuilder selectSeatWithRetry(", mix);
        final String mixFlow = flow.substring(mix, mixEnd);

        final int dropout = mixFlow.indexOf("shouldDropBeforeOrder()");
        final int release = mixFlow.indexOf("releaseAllSelections()", dropout);
        final int order = mixFlow.indexOf("createOrderAllowingConflict(false)");
        final int cancel = mixFlow.indexOf("shouldCancelOrder()");
        final int close = mixFlow.indexOf("closeSeatSocket()");
        assertTrue(dropout < release && release < order && order < cancel && cancel < close);
    }

    private static String source(final String fileName) throws IOException {
        return Files.readString(SIMULATION_ROOT.resolve(fileName), StandardCharsets.UTF_8);
    }
}
