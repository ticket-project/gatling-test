package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(html.contains("id=\"coreBaseUrl\" name=\"coreBaseUrl\" value=\"https://oneticket.site\""));
        assertTrue(html.contains("id=\"queueBaseUrl\" name=\"queueBaseUrl\" value=\"https://queue.oneticket.site\""));
        assertTrue(html.contains("id=\"bookingFeederFile\" name=\"bookingFeederFile\""));
        assertTrue(html.contains("id=\"resultFile\" name=\"resultFile\""));
        assertTrue(html.contains("id=\"pollingTimeoutSeconds\" name=\"pollingTimeoutSeconds\""));
        assertTrue(html.contains("id=\"distributedRemoteProjectDir\" name=\"distributedRemoteProjectDir\""));
        assertTrue(html.contains("id=\"operationalConfirmation\" name=\"operationalConfirmation\""));
        assertTrue(html.contains("setVisible('[data-option=\"booking\"]', selected?.usesCoreBookingFlow);"));
        assertTrue(html.contains("setVisible('[data-option=\"booking-feeder\"]', selected?.usesBookingFeeder);"));
        assertTrue(html.contains("isLocalhostUrl(coreBaseUrl)"));
        assertTrue(html.contains("body.get('operationalConfirmation') !== 'on'"));
        assertTrue(html.contains("${queueBase}/join"));
        assertTrue(html.contains("${queueBase}/state"));
        assertTrue(html.contains("${queueBase}/enter"));
        assertTrue(html.contains("${performanceBase}/seats/status"));
        assertTrue(html.contains("${performanceBase}/seats/{seatId}/select"));
        assertTrue(html.contains("${coreBaseUrl}/api/v1/orders"));
        assertTrue(html.contains("${coreBaseUrl}/api/v1/orders/{orderKey}"));
        assertFalse(html.contains("${coreBaseUrl}/api/v1/members"));
        assertTrue(html.contains("id=\"jwtSecret\" name=\"jwtSecret\" type=\"password\" value=\"0123456789abcdef0123456789abcdef\" autocomplete=\"off\""));
        assertFalse(html.contains("id=\"jwtSecret\" name=\"jwtSecret\" type=\"password\" value=\"0123456789abcdef0123456789abcdef\" readonly"));
        assertTrue(html.contains("id=\"stopRun\""));
        assertTrue(html.contains("/api/runs/${selectedRunId}/stop"));
        assertTrue(html.contains("badge-STOPPED"));
        assertFalse(html.contains("id=\"environmentCaptureEnabled\""));
        assertFalse(html.contains("id=\"datadogEnv\""));
        assertFalse(html.contains("id=\"datadogService\""));
        assertFalse(html.contains("id=\"datadogContainerName\""));
        assertFalse(html.contains("id=\"jvmXmsMiBOverride\""));
        assertFalse(html.contains("id=\"tomcatMaxThreadsOverride\""));
        assertFalse(html.contains("id=\"oracleInstanceProfile\""));
        assertFalse(html.contains("id=\"redisNetworkLocation\""));
        assertTrue(html.contains("const targets = Array.isArray(metadata.targets)"));
        assertTrue(html.contains("const instances = groupedSchema ? group.instances : [group]"));
        assertTrue(html.contains("group.replicaCountObserved"));
        assertTrue(html.contains("evidenceDisplay"));
        assertTrue(html.contains("id=\"environmentRows\""));
        assertTrue(html.contains("id=\"downloadEnvironment\""));
        assertFalse(html.contains("name=\"DATADOG_API_KEY\""));
        assertFalse(html.contains("name=\"DATADOG_APP_KEY\""));
    }

    @Test
    void exposesProofSuiteConsoleControls() throws IOException {
        final String html = Files.readString(Path.of("src/main/resources/static/index.html"));
        final String compactHtml = html.replaceAll("\\s+", "");
        assertTrue(html.contains("'hot-seat-concurrency': ["));
        assertTrue(compactHtml.contains("'core-admission-capacity':["
                + "['GET',`${performanceBase}/summary`],"
                + "['GET',`${performanceBase}/seats/status`],"
                + "['POST',`${performanceBase}/seats/{seatId}/select`],"
                + "['POST',`${coreBaseUrl}/api/v1/orders`],"
                + "['GET',`${coreBaseUrl}/api/v1/orders/{orderKey}`]]"));
        assertTrue(html.contains("'core-active-users-closed': ["));
        assertTrue(html.contains("'core-spike': ["));
        assertTrue(html.contains("'queue-protects-core': ["));
        assertTrue(html.contains("id=\"bookingFeederRows\" name=\"bookingFeederRows\""));
        assertTrue(html.contains("<option value=\"spike\">"));
        assertTrue(html.contains("<option value=\"closed-core\">"));
        assertTrue(html.contains("const proofSimulationDefaults ="));
        assertTrue(html.contains("applyProofSimulationDefaults()"));
        assertTrue(html.contains("selected?.key === 'core-active-users-closed'"));
        assertTrue(html.contains("function coreSpikeExpectedUsers("));
        assertTrue(html.contains("Closed Model \ud53c\ub354 \ud589 \uc218\ub294 \ub3d9\uc2dc \uc0ac\uc6a9\uc790 \uc218 \uc774\uc0c1"));
        assertTrue(html.contains("id=\"targetRouteSummary\""));
        assertTrue(html.contains("label[for=\"baseUrl\"], #baseUrl"));
        assertFalse(html.contains("<label for=\"ticketProjectPath\">"));
        assertTrue(html.contains("id=\"ticketProjectPath\" name=\"ticketProjectPath\" type=\"hidden\""));
        assertTrue(html.contains("setInputContainerVisible('queueTimeoutThresholdPercent', selected?.usesQueueBaseUrl)"));
        assertFalse(html.contains("dbAuditEnabled"));
        assertTrue(html.contains("setInputContainerVisible('resultFile', false)"));
        assertTrue(html.contains("simulationSelect.value = 'smoke'"));
        assertTrue(html.contains("'hot-seat-concurrency': { coreBaseUrl: 'https://oneticket.site', users: 10"));
        assertTrue(html.contains("resultFile: '../../distributed-results-join/_latest/core-spike.csv'"));
        assertTrue(html.contains("resultFile: '../../distributed-results-join/_latest/core-admission-capacity.csv'"));
    }
}
