package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleIndexHtmlTest {

    @Test
    void exposesTicketServerCapacityAndAdmissionTokenControls() throws IOException {
        final String html = Files.readString(Path.of("src/main/resources/static/index.html"));

        assertTrue(html.contains("티켓 서버 용량"));
        assertTrue(html.contains("value=\"C:\\Users\\mn040\\IdeaProjects\\ticket-workspace\\gatling-test\""));
        assertTrue(html.contains("value=\"http://52.237.82.8:18090/legacy-queue\""));
        assertTrue(html.contains("https://queue.oneticket.site"));
        assertTrue(html.contains("defaultBaseUrl"));
        assertTrue(html.contains("applySimulationDefaultBaseUrl(); updateVisibility(); updateSummary();"));
        assertTrue(html.contains("legacy-queue-status"));
        assertTrue(html.contains("${queueBase}/status"));
        assertTrue(html.contains("cdn-public-state"));
        assertTrue(html.contains("${queueBase}/state"));
        assertTrue(html.contains("admissionTokenMode"));
        assertTrue(html.contains("Admission Token 목록"));
        assertTrue(html.contains("X-Admission-Token"));
    }
}
