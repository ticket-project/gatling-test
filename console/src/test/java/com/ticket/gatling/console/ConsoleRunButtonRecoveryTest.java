package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleRunButtonRecoveryTest {

    @Test
    void restoresRunButtonWhenRunRequestOrPollingFails() throws IOException {
        final String html = Files.readString(Path.of("src/main/resources/static/index.html"));

        assertTrue(html.contains("async function startRun(event)"));
        assertTrue(html.contains("try {"));
        assertTrue(html.contains("catch (error) {"));
        assertTrue(html.contains("restoreIdleState(error.message || 'Run failed to start');"));
        assertTrue(html.contains("async function refreshRunDetail()"));
        assertTrue(html.contains("restoreIdleState(error.message || 'Failed to refresh run status');"));
    }
}
