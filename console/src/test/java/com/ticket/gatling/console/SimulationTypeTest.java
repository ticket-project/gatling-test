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
    @Test
    void exposesBookingSimulationsForConsoleSelection() {
        final SimulationType capacity = SimulationType.fromKey("booking-capacity");
        final SimulationType endToEnd = SimulationType.fromKey("ticket-open-end-to-end");
        final SimulationType contention = SimulationType.fromKey("seat-contention");

        assertEquals("com.ticket.loadtest.simulation.BookingCapacitySimulation", capacity.className());
        assertEquals("BOOKING_CAPACITY", capacity.bookingScenario());
        assertEquals("com.ticket.loadtest.simulation.TicketOpenEndToEndSimulation", endToEnd.className());
        assertEquals("TICKET_OPEN_END_TO_END", endToEnd.bookingScenario());
        assertEquals("com.ticket.loadtest.simulation.SeatContentionSimulation", contention.className());
        assertEquals("SEAT_CONTENTION", contention.bookingScenario());
        assertTrue(capacity.usesBookingFeeder());
        assertTrue(endToEnd.usesQueueBaseUrl());
        assertFalse(contention.usesAccessTokens());
    }
    @Test
    void exposesProofSuiteSimulationsForConsoleSelection() {
        assertEquals("SMOKE", SimulationType.fromKey("smoke").bookingScenario());
        assertEquals("HOT_SEAT_CONCURRENCY",
                SimulationType.fromKey("hot-seat-concurrency").bookingScenario());
        assertEquals("CORE_ADMISSION_CAPACITY",
                SimulationType.fromKey("core-admission-capacity").bookingScenario());
        assertEquals("CORE_ACTIVE_USERS_CLOSED",
                SimulationType.fromKey("core-active-users-closed").bookingScenario());
        assertEquals("CORE_SPIKE", SimulationType.fromKey("core-spike").bookingScenario());
        assertEquals("QUEUE_PROTECTS_CORE",
                SimulationType.fromKey("queue-protects-core").bookingScenario());
        assertTrue(SimulationType.fromKey("core-active-users-closed").usesBookingFeeder());
        assertTrue(SimulationType.fromKey("queue-protects-core").usesQueueBaseUrl());
    }

}
