package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationTypeTest {

    @Test
    void hasDefaultBaseUrlsForLegacyAndCdnSimulations() {
        assertEquals("http://52.237.82.8:18090/legacy-queue", SimulationType.LEGACY_QUEUE_STATUS.defaultBaseUrl());
        assertEquals("https://queue.oneticket.site", SimulationType.CDN_PUBLIC_STATE.defaultBaseUrl());
    }

    @Test
    void exposesQueueJoinOnlySimulationForConsoleSelection() {
        final SimulationType type = SimulationType.fromKey("queue-join-only");

        assertEquals("com.ticket.loadtest.simulation.QueueJoinOnlySimulation", type.className());
        assertEquals("https://queue.oneticket.site", type.defaultBaseUrl());
        assertTrue(type.usesAccessTokens());
        assertFalse(type.usesSeatIds());
        assertFalse(type.usesAdmissionTokens());
        assertFalse(type.usesStatusPolling());
    }
}
