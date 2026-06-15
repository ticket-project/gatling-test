package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimulationTypeTest {

    @Test
    void hasDefaultBaseUrlsForLegacyAndCdnSimulations() {
        assertEquals("http://52.237.82.8:18090/legacy-queue", SimulationType.LEGACY_QUEUE_STATUS.defaultBaseUrl());
        assertEquals("http://52.237.82.8", SimulationType.CDN_PUBLIC_STATE.defaultBaseUrl());
    }
}
