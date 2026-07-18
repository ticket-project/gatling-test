package com.ticket.loadtest.simulation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeatContentionSimulationTest {

    private static final Path SIMULATION_SOURCE = Path.of(
            "src/gatling/java/com/ticket/loadtest/simulation/SeatContentionSimulation.java"
    );

    @Test
    void continuesFromExpectedSelectContentionAndRejectsOtherResponsesTechnically() throws IOException {
        final String source = source();

        assertTrue(source.contains(".feed(LoadTestConfig.bookingFeeder())"));
        assertTrue(source.contains(".headers(LoadTestConfig.authAndAdmissionHeaders())"));
        assertTrue(source.contains("jsonPath(\"$.error.code\").optional().saveAs(\"selectErrorCode\")"));
        assertTrue(source.contains("httpStatus == 409 && SELECT_REJECTION_CODE.equals(errorCode)"));
        assertTrue(source.contains("SELECT_REJECTION_CODE = \"E4001\""));
        assertTrue(source.contains("SELECT_BUSINESS_REJECTED_E4001"));
        assertTrue(source.contains("dummy(\"select seat technical failure\", 0)"));
        assertTrue(source.indexOf(".exec(selectSeat())") < source.indexOf(".exec(createOrder())"));
    }

    @Test
    void separatesAllowedOrderBusinessRejectionsFromTechnicalFailures() throws IOException {
        final String source = source();

        assertTrue(source.contains("Set.of(\"E5000\", \"E5001\", \"E6000\", \"E6003\")"));
        assertTrue(source.contains("jsonPath(\"$.error.code\").optional().saveAs(\"orderErrorCode\")"));
        assertTrue(source.contains("httpStatus == 409 && ORDER_REJECTION_CODES.contains(errorCode)"));
        assertTrue(source.contains("httpStatus != 201 && !businessRejected"));
        assertTrue(source.contains("dummy(\"create order technical failure\", 0)"));
        assertTrue(source.contains("\"BUSINESS_REJECTED_\" + session.getString(\"orderErrorCode\")"));
        assertTrue(source.contains("BookingResultRecorder.append("));
        assertFalse(source.contains("status().in(201, 400, 409, 422)"));
    }

    @Test
    void verifiesSuccessfulOrdersAndUsesMonotonicBoundedPolling() throws IOException {
        final String source = source();

        assertTrue(source.contains("header(\"X-Order-Key\").optional().saveAs(\"orderKeyHeader\")"));
        assertTrue(source.contains("jsonPath(\"$.data.orderKey\").optional().saveAs(\"orderKeyBody\")"));
        assertTrue(source.contains("!headerOrderKey.equals(bodyOrderKey)"));
        assertTrue(source.contains("Duration.ofSeconds(5)"));
        assertTrue(source.contains("Duration.ofMillis(200)"));
        assertTrue(source.contains("System.nanoTime() + timeout.toNanos()"));
        assertTrue(source.contains("remainingNanos(session, \"orderDeadlineNanos\") > 0"));
        assertTrue(source.contains("Math.min(requestedPause.toNanos(), remainingNanos)"));
        assertTrue(source.contains("\"PENDING\".equals(session.getString(\"orderStatus\"))"));
        assertTrue(source.contains("recordResult(\"orderKey\", \"orderHttpStatus\", \"SUCCESS\")"));
        assertFalse(source.contains(".asLongAsDuring("));
        assertFalse(source.contains(".pause(ORDER_POLL_PAUSE)"));
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