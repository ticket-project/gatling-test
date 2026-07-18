package com.ticket.loadtest.simulation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingCapacitySimulationTest {

    private static final Path SIMULATION_SOURCE = Path.of(
            "src/gatling/java/com/ticket/loadtest/simulation/BookingCapacitySimulation.java"
    );

    @Test
    void runsTheCapacityFlowWithFeederTokensAndStopsOnTechnicalFailure() throws IOException {
        final String source = source();

        assertTrue(source.contains(".feed(LoadTestConfig.bookingFeeder())"));
        assertTrue(source.contains("/performances/#{performanceId}/seats/status"));
        assertTrue(source.contains("/performances/#{performanceId}/seats/#{seatId}/select"));
        assertTrue(source.contains(".post(\"/api/v1/orders\")"));
        assertTrue(source.contains(".headers(LoadTestConfig.authAndAdmissionHeaders())"));
        assertEquals(3, count(source, ".check(status().is(200))"));
        assertEquals(1, count(source, ".check(status().is(201))"));
        assertTrue(count(source, ".exitHereIfFailed()") >= 6);
        assertFalse(source.contains("status().in("));
    }

    @Test
    void verifiesOrderKeyAndPollsPendingForAtMostFiveSeconds() throws IOException {
        final String source = source();

        assertTrue(source.contains("header(\"X-Order-Key\").optional().saveAs(\"orderKeyHeader\")"));
        assertTrue(source.contains("jsonPath(\"$.data.orderKey\").optional().saveAs(\"orderKeyBody\")"));
        assertTrue(source.contains("!headerOrderKey.equals(bodyOrderKey)"));
        assertTrue(source.contains("session.set(\"orderKeyContractFailure\", true)"));
        assertTrue(source.contains("doIf(session -> session.getBoolean(\"orderKeyContractFailure\"))"));
        assertTrue(source.contains("Duration.ofSeconds(5)"));
        assertTrue(source.contains("Duration.ofMillis(200)"));
        assertTrue(source.contains("\"PENDING\".equals(session.getString(\"orderStatus\"))"));
        assertTrue(source.contains("doIf(session -> !session.getBoolean(\"orderPending\"))"));
        assertTrue(source.contains("dummy(\"order key contract failure\", 0)"));
        assertTrue(source.contains("dummy(\"order state timeout\", 0)"));
        assertEquals(2, count(source, ".withSuccess(false)"));
        assertEquals(2, count(source, ".withSessionUpdate(session -> session.markAsFailed())"));
        assertTrue(source.contains("BookingResultRecorder.append("));
    }

    @Test
    void enforcesTicketLatencyAndTechnicalFailureSlos() throws IOException {
        final String source = source();

        assertTrue(source.contains("global().failedRequests().percent().lt(1.0)"));
        assertEquals(4, count(source, "responseTime().percentile(99.0).lt(3000)"));
    }

    private static String source() throws IOException {
        return Files.readString(SIMULATION_SOURCE, StandardCharsets.UTF_8);
    }

    private static int count(final String source, final String value) {
        return (source.length() - source.replace(value, "").length()) / value.length();
    }
}
