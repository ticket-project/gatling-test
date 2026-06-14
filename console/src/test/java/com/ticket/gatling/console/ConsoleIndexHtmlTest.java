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
        assertTrue(html.contains("legacy-queue-status"));
        assertTrue(html.contains("${queueBase}/status"));
        assertTrue(html.contains("cdn-public-state"));
        assertTrue(html.contains("/queue-state/performances/${performanceId}.json"));
        assertTrue(html.contains("admissionTokenMode"));
        assertTrue(html.contains("Admission Token 목록"));
        assertTrue(html.contains("X-Admission-Token"));
    }
}
