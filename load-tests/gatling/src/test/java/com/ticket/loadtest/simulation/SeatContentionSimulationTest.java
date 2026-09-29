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
        assertTrue(source.contains("CoreRejections.ofSelect(httpStatus, errorCode)"));
        assertTrue(source.contains("\"SELECT_\" + CoreRejections.resultName("));
        assertTrue(source.contains(".exitHereIf(session -> session.getBoolean(\"selectOverloaded\"))"));
        assertTrue(source.contains("dummy(\"select seat technical failure\", 0)"));
        assertTrue(source.indexOf(".exec(selectSeat())") < source.indexOf(".exec(createOrder())"));
    }

    @Test
    void separatesAllowedOrderBusinessRejectionsFromTechnicalFailures() throws IOException {
        final String source = source();

        assertFalse(source.contains("E5000"));
        assertTrue(source.contains("jsonPath(\"$.error.code\").optional().saveAs(\"orderErrorCode\")"));
        assertTrue(source.contains("CoreRejections.ofOrder(httpStatus, errorCode)"));
        assertTrue(source.contains("httpStatus != 201 && !businessRejected && !overloaded"));
        assertTrue(source.contains("dummy(\"create order technical failure\", 0)"));
        assertTrue(source.contains("CoreRejections.resultName(CoreRejections.Kind.BUSINESS_REJECTED, session.getString(\"orderErrorCode\"))"));
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
    void enforcesTechnicalFailureOnlyWithoutResponseTimeAssertions() throws IOException {
        final String source = source();

        assertTrue(source.contains("global().failedRequests().percent().lt(1.0)"));
        assertEquals(0, count(source, "responseTime()"));
    }

    private static String source() throws IOException {
        return Files.readString(SIMULATION_SOURCE, StandardCharsets.UTF_8);
    }

    private static int count(final String source, final String value) {
        return (source.length() - source.replace(value, "").length()) / value.length();
    }
}