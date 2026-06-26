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
        assertTrue(html.contains("queue-join-only"));
        assertTrue(html.contains("${queueBase}/join"));
        assertTrue(html.contains("legacy-queue-status"));
        assertTrue(html.contains("${queueBase}/status"));
        assertTrue(html.contains("cdn-public-state"));
        assertTrue(html.contains("${queueBase}/state"));
        assertTrue(html.contains("data-option=\"poll-jitter\""));
        assertTrue(html.contains("name=\"statusPollPauseJitterSeconds\" type=\"number\" min=\"0\" value=\"0\""));
        assertTrue(html.contains("setVisible('[data-option=\"poll-jitter\"]', selected?.usesStatusPolling);"));
        assertTrue(html.contains("id=\"executionMode\" name=\"executionMode\""));
        assertTrue(html.contains("value=\"distributed\""));
        assertTrue(html.contains("id=\"distributedHosts\" name=\"distributedHosts\""));
        assertTrue(html.contains("ubuntu@43.203.155.15"));
        assertTrue(html.contains("id=\"distributedIncludeLocal\""));
        assertTrue(html.indexOf("id=\"distributedCollectReports\"")
                < html.indexOf("<input name=\"distributedCollectReports\" type=\"hidden\""));
        assertTrue(html.contains("id=\"accessTokenSource\" name=\"accessTokenSource\""));
        assertTrue(html.contains("value=\"generate-file\""));
        assertTrue(html.contains("id=\"generatedAccessTokenCount\" name=\"generatedAccessTokenCount\""));
        assertTrue(html.contains("id=\"accessTokensFile\" name=\"accessTokensFile\""));
        assertTrue(html.contains("data-token-config=\"jwt\""));
        assertTrue(html.contains("normalizeAccessTokenSourceForRun(body);"));
        assertTrue(html.contains("admissionTokenMode"));
        assertTrue(html.contains("Admission Token 목록"));
        assertTrue(html.contains("X-Admission-Token"));
    }
}
