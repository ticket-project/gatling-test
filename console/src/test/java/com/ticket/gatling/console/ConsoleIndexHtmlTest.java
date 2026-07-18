package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleIndexHtmlTest {

    @Test
    void exposesBookingLoadTestConsoleControls() throws IOException {
        final String html = Files.readString(Path.of("src/main/resources/static/index.html"));

        assertTrue(html.contains("value=\"C:\\Users\\mn040\\IdeaProjects\\ticket-workspace\\gatling-test\""));
        assertTrue(html.contains("queue-join-only"));
        assertTrue(html.contains("legacy-queue-status"));
        assertTrue(html.contains("cdn-public-state"));
        assertTrue(html.contains("booking-capacity"));
        assertTrue(html.contains("ticket-open-end-to-end"));
        assertTrue(html.contains("seat-contention"));
        assertTrue(html.contains("id=\"coreBaseUrl\" name=\"coreBaseUrl\""));
        assertTrue(html.contains("id=\"queueBaseUrl\" name=\"queueBaseUrl\""));
        assertTrue(html.contains("id=\"bookingFeederFile\" name=\"bookingFeederFile\""));
        assertTrue(html.contains("id=\"resultFile\" name=\"resultFile\""));
        assertTrue(html.contains("id=\"pollingTimeoutSeconds\" name=\"pollingTimeoutSeconds\""));
        assertTrue(html.contains("id=\"distributedRemoteProjectDir\" name=\"distributedRemoteProjectDir\""));
        assertTrue(html.contains("id=\"operationalConfirmation\" name=\"operationalConfirmation\""));
        assertTrue(html.contains("setVisible('[data-option=\"booking\"]', selected?.usesBookingFeeder);"));
        assertTrue(html.contains("isLocalhostUrl(coreBaseUrl)"));
        assertTrue(html.contains("body.get('operationalConfirmation') !== 'on'"));
        assertTrue(html.contains("${queueBase}/join"));
        assertTrue(html.contains("${queueBase}/state"));
        assertTrue(html.contains("${queueBase}/enter"));
        assertTrue(html.contains("${performanceBase}/seats/status"));
        assertTrue(html.contains("${performanceBase}/seats/{seatId}/select"));
        assertTrue(html.contains("${coreBaseUrl}/api/v1/orders"));
        assertTrue(html.contains("${coreBaseUrl}/api/v1/orders/{orderKey}"));
    }
}