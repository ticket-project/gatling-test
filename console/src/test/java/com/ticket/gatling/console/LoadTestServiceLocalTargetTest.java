package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadTestServiceLocalTargetTest {
    @TempDir
    Path tempDir;

    private LoadTestRequest summaryRequest(final String coreBaseUrl, final boolean confirmed) {
        return LoadTestRequest.fromForm(Map.ofEntries(
                Map.entry("ticketProjectPath", List.of(tempDir.toString())),
                Map.entry("simulation", List.of("core-performance-summary-api")),
                Map.entry("coreBaseUrl", List.of(coreBaseUrl)),
                Map.entry("performanceId", List.of("910000001")),
                Map.entry("injectionMode", List.of("constant-users-per-sec")),
                Map.entry("usersPerSecond", List.of("1")),
                Map.entry("durationSeconds", List.of("1")),
                Map.entry("operationalConfirmation", List.of(confirmed ? "on" : "off"))
        ));
    }

    private LoadTestRequest seatStatusRequest(final String coreBaseUrl) {
        return LoadTestRequest.fromForm(Map.ofEntries(
                Map.entry("ticketProjectPath", List.of(tempDir.toString())),
                Map.entry("simulation", List.of("core-seat-status-api")),
                Map.entry("coreBaseUrl", List.of(coreBaseUrl)),
                Map.entry("performanceId", List.of("910000001")),
                Map.entry("accessTokenMode", List.of("tokens")),
                Map.entry("accessTokenSource", List.of("generate-file")),
                Map.entry("jwtSecret", List.of("0123456789abcdef0123456789abcdef")),
                Map.entry("memberIdsFile", List.of("")),
                Map.entry("injectionMode", List.of("constant-users-per-sec")),
                Map.entry("usersPerSecond", List.of("1")),
                Map.entry("durationSeconds", List.of("1")),
                Map.entry("operationalConfirmation", List.of("on"))
        ));
    }

    @Test
    void acceptsLocalhostCoreUrlWithoutOperationalConfirmation() {
        assertDoesNotThrow(() ->
                LoadTestService.validateTargetForTest(summaryRequest("http://localhost:8080", false)));
    }

    @Test
    void acceptsLoopbackAddressCoreUrl() {
        assertDoesNotThrow(() ->
                LoadTestService.validateTargetForTest(summaryRequest("http://127.0.0.1:8080", false)));
    }

    @Test
    void stillRequiresOperationalConfirmationForRemoteCoreUrl() {
        final IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> LoadTestService.validateTargetForTest(summaryRequest("https://oneticket.site", false))
        );

        assertTrue(exception.getMessage().contains("confirmation"));
    }

    @Test
    void acceptsRemoteCoreUrlWithOperationalConfirmation() {
        assertDoesNotThrow(() ->
                LoadTestService.validateTargetForTest(summaryRequest("https://oneticket.site", true)));
    }

    @Test
    void localTargetDoesNotRequireMemberIdFile() {
        assertDoesNotThrow(() ->
                LoadTestService.validateJwtForTest(seatStatusRequest("http://localhost:8080")));
    }

    @Test
    void remoteTargetStillRequiresMemberIdFile() {
        final IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> LoadTestService.validateJwtForTest(seatStatusRequest("https://oneticket.site"))
        );

        assertTrue(exception.getMessage().contains("Member ID"));
    }
}
