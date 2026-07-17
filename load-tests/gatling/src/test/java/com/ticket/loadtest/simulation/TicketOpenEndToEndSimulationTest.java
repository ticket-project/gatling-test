package com.ticket.loadtest.simulation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketOpenEndToEndSimulationTest {

    private static final Path SIMULATION_SOURCE = Path.of(
            "src/gatling/java/com/ticket/loadtest/simulation/TicketOpenEndToEndSimulation.java"
    );

    @Test
    void waitsForTheUsersShardBeforeEnteringTheQueue() throws IOException {
        final String source = source();

        assertTrue(source.contains(".feed(LoadTestConfig.bookingFeeder())"));
        assertTrue(source.contains("jsonPath(\"$.queueToken\").saveAs(\"queueToken\")"));
        assertTrue(source.contains("jsonPath(\"$.shardId\").saveAs(\"shardId\")"));
        assertTrue(source.contains("jsonPath(\"$.localSeq\").ofLong().saveAs(\"localSeq\")"));
        assertTrue(source.contains("jsonPath(\"$.serving['#{shardId}']\").ofLong().optional()"));
        assertTrue(source.contains("servingSeq < session.getLong(\"lastServingSeq\")"));
        assertTrue(source.contains("servingSeq >= session.getLong(\"localSeq\")"));
        assertTrue(source.contains("jsonPath(\"$.refreshAfterMs\").ofLong().optional()"));
        assertTrue(source.contains("LoadTestConfig.statusPollPauseMin()"));
        assertTrue(source.contains("LoadTestConfig.statusPollPauseMax()"));
        assertTrue(source.contains("ThreadLocalRandom.current().nextLong"));
        assertTrue(source.contains("jsonPath(\"$.data.admissionToken\").saveAs(\"admissionToken\")"));
    }

    @Test
    void recordsQueueTimeoutOutsideGatlingRequestMetricsAndStopsBeforeTicketCalls() throws IOException {
        final String source = source();

        assertTrue(source.contains("Duration.ofSeconds(LoadTestConfig.pollingTimeoutSeconds())"));
        assertFalse(source.contains("dummy(\"QUEUE_TIMEOUT\""));
        assertTrue(source.contains("recordQueueTimeout()"));
        final int recorder = source.indexOf("private ChainBuilder recordQueueTimeout()");
        final int recorderEnd = source.indexOf("        });", recorder);
        final String recorderBlock = source.substring(recorder, recorderEnd);
        assertTrue(recorderBlock.contains("null,"));
        assertTrue(recorderBlock.contains("0,"));
        assertTrue(recorderBlock.contains("\"QUEUE_TIMEOUT\""));
        assertTrue(source.contains("return session.markAsFailed()"));
        final int timeout = source.indexOf(".exec(doIf(session -> !session.getBoolean(\"admissionReady\"))");
        final int stop = source.indexOf(".exitHereIfFailed()", timeout);
        final int ticket = source.indexOf(".exec(fetchSeatStatus())", stop);
        assertTrue(timeout < stop && stop < ticket);
    }

    @Test
    void clampsConditionalPausesToMonotonicDeadlines() throws IOException {
        final String source = source();

        assertTrue(source.contains("System.nanoTime() + timeout.toNanos()"));
        assertTrue(source.contains("remainingNanos(session, \"queueDeadlineNanos\") > 0"));
        assertTrue(source.contains("remainingNanos(session, \"orderDeadlineNanos\") > 0"));
        assertTrue(source.contains("Math.min(requestedPause.toNanos(), remainingNanos)"));
        assertTrue(source.contains(".doIf(session -> !session.getBoolean(\"admissionReady\")"));
        assertTrue(source.contains(".doIf(session -> !session.getBoolean(\"orderPending\")"));
        final int queueUpdate = source.indexOf(".exec(updateQueueState())");
        final int queuePause = source.indexOf(
                ".doIf(session -> !session.getBoolean(\"admissionReady\")",
                queueUpdate
        );
        final int orderUpdate = source.indexOf("\"PENDING\".equals(session.getString(\"orderStatus\"))");
        final int orderPause = source.indexOf(
                ".doIf(session -> !session.getBoolean(\"orderPending\")",
                orderUpdate
        );
        assertTrue(queueUpdate < queuePause);
        assertTrue(orderUpdate < orderPause);
        assertFalse(source.contains(".asLongAsDuring("));
        assertFalse(source.contains(".pause(ORDER_POLL_PAUSE)"));
        assertFalse(source.contains(".pause(session -> Duration.ofMillis(session.getLong(\"queuePollDelayMs\")))"));
    }

    @Test
    void completesTheTicketFlowAndVerifiesTheOrderContract() throws IOException {
        final String source = source();

        assertTrue(source.contains("/performances/#{performanceId}/seats/status"));
        assertTrue(source.contains("/performances/#{performanceId}/seats/#{seatId}/select"));
        assertTrue(source.contains(".post(\"/api/v1/orders\")"));
        assertTrue(source.contains("header(\"X-Order-Key\").optional().saveAs(\"orderKeyHeader\")"));
        assertTrue(source.contains("jsonPath(\"$.data.orderKey\").optional().saveAs(\"orderKeyBody\")"));
        assertTrue(source.contains("!headerOrderKey.equals(bodyOrderKey)"));
        assertTrue(source.contains("Duration.ofSeconds(5)"));
        assertTrue(source.contains("Duration.ofMillis(200)"));
        assertTrue(source.contains("\"PENDING\".equals(session.getString(\"orderStatus\"))"));
        assertTrue(source.contains("BookingResultRecorder.append("));
        assertTrue(source.contains(".headers(LoadTestConfig.authAndAdmissionHeaders())"));
        assertFalse(source.contains("status().in("));
        assertEquals(6, count(source, ".check(status().is(200))"));
        assertEquals(1, count(source, ".check(status().is(201))"));
    }

    @Test
    void enforcesQueueTicketAndTechnicalFailureSlos() throws IOException {
        final String source = source();

        assertTrue(source.contains("global().failedRequests().percent().lt(1.0)"));
        assertEquals(3, count(source, "responseTime().percentile(99.0).lt(2000)"));
        assertEquals(4, count(source, "responseTime().percentile(99.0).lt(3000)"));
    }

    private static String source() throws IOException {
        return Files.readString(SIMULATION_SOURCE, StandardCharsets.UTF_8);
    }

    private static int count(final String source, final String value) {
        return (source.length() - source.replace(value, "").length()) / value.length();
    }
}
