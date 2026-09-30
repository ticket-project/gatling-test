package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoadTestRequestTest {

    @Test
    void defaultsTicketProjectPathToGatlingTestRepositoryRoot() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of());

        assertEquals(
                Path.of("C:\\Users\\mn040\\IdeaProjects\\ticket-workspace\\gatling-test").toAbsolutePath().normalize(),
                request.ticketProjectPath()
        );
        assertEquals(
                request.ticketProjectPath().resolve("distributed-results-join"),
                request.reportsRoot()
        );
        assertEquals("../../distributed-results-join/_latest/booking-results.csv", request.resultFile());
    }

    @Test
    void leavesCdnBaseUrlBlankUntilExplicitlyEntered() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("cdn-public-state")
        ));

        assertEquals("", request.baseUrl());
    }

    @Test
    void readsStatusPollPauseJitterFromForm() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "statusPollPauseJitterSeconds", List.of("4")
        ));

        assertEquals(4, request.statusPollPauseJitterSeconds());
    }

    @Test
    void defaultsStatusPollPauseJitterToZero() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of());

        assertEquals(0, request.statusPollPauseJitterSeconds());
    }

    @Test
    void readsAccessTokenFileFromForm() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "accessTokensFile", List.of("C:/tokens/access-tokens.txt")
        ));

        assertEquals("C:/tokens/access-tokens.txt", request.accessTokensFile());
        assertEquals("file", request.accessTokenSource());
    }

    @Test
    void defaultsGeneratedTokenCountToEstimatedVirtualUsers() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "accessTokenMode", List.of("tokens"),
                "accessTokenSource", List.of("generate-file"),
                "injectionMode", List.of("constant-users-per-sec"),
                "usersPerSecond", List.of("7.5"),
                "durationSeconds", List.of("60")
        ));

        assertEquals(450, request.generatedAccessTokenCount());
        assertEquals(true, request.generatesAccessTokensFile());
    }

    @Test
    void fallsBackToGeneratedTokenFileWhenInlineSourceHasNoTokens() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "accessTokenMode", List.of("tokens"),
                "accessTokenSource", List.of("inline"),
                "accessTokens", List.of("")
        ));

        assertEquals("generate-file", request.accessTokenSource());
        assertEquals(true, request.generatesAccessTokensFile());
    }

    @Test
    void readsJwtSecretFromFormForSyntheticJwtMode() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "accessTokenMode", List.of("synthetic-jwt"),
                "jwtSecret", List.of("local-queue-jwt-secret-1234567890")
        ));

        assertEquals("local-queue-jwt-secret-1234567890", request.jwtSecret());
    }
    @Test
    void readsDistributedExecutionOptionsFromForm() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "executionMode", List.of("distributed"),
                "distributedIncludeLocal", List.of("on"),
                "distributedCollectReports", List.of("on"),
                "distributedDumpFailureBody", List.of("on"),
                "distributedDumpFailureBodyLimit", List.of("2"),
                "distributedHosts", List.of("43.203.155.15\nubuntu@15.165.40.25")
        ));

        assertEquals("distributed", request.executionMode());
        assertEquals(true, request.distributedExecution());
        assertEquals(true, request.distributedIncludeLocal());
        assertEquals(true, request.distributedCollectReports());
        assertEquals(true, request.distributedDumpFailureBody());
        assertEquals(2, request.distributedDumpFailureBodyLimit());
        assertEquals(List.of("ubuntu@43.203.155.15", "ubuntu@15.165.40.25"), request.distributedHostList());
    }

    @Test
    void estimatesVirtualUsersForConstantRateInjection() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "injectionMode", List.of("constant-users-per-sec"),
                "users", List.of("10"),
                "usersPerSecond", List.of("7.5"),
                "durationSeconds", List.of("60")
        ));

        assertEquals(450, request.estimatedVirtualUsers());
    }

    @Test
    void estimatesVirtualUsersForRampRateInjection() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "injectionMode", List.of("ramp-users-per-sec"),
                "usersPerSecond", List.of("5"),
                "targetUsersPerSecond", List.of("15"),
                "durationSeconds", List.of("60")
        ));

        assertEquals(600, request.estimatedVirtualUsers());
    }
    @Test
    void doesNotReuseGenericQueueUrlAsBookingCoreUrl() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("core-admission-capacity"),
                "baseUrl", List.of("https://queue.oneticket.site")
        ));

        assertEquals("", request.coreBaseUrl());
        assertEquals("", request.queueBaseUrl());
    }
    @Test
    void readsBookingExecutionOptionsFromForm() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("ticket-open-end-to-end"),
                "coreBaseUrl", List.of("https://api.example.com"),
                "queueBaseUrl", List.of("https://queue.example.com"),
                "bookingFeederFile", List.of("C:/feeders/booking.csv"),
                "resultFile", List.of("build/results/node-2.csv"),
                "pollingTimeoutSeconds", List.of("240"),
                "distributedRemoteProjectDir", List.of("~/gatling-booking"),
                "operationalConfirmation", List.of("on")
        ));

        assertEquals("https://api.example.com", request.coreBaseUrl());
        assertEquals("https://queue.example.com", request.queueBaseUrl());
        assertEquals("C:/feeders/booking.csv", request.bookingFeederFile());
        assertEquals("build/results/node-2.csv", request.resultFile());
        assertEquals(240, request.pollingTimeoutSeconds());
        assertEquals("~/gatling-booking", request.distributedRemoteProjectDir());
        assertEquals(true, request.operationalConfirmation());
    }

    @Test
    void readsCoreCapacityOffsetAndFailureThresholdOverrides() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.ofEntries(
                Map.entry("simulation", List.of("core-admission-capacity")),
                Map.entry("bookingFeederOffset", List.of("300")),
                Map.entry("memberIdsFile", List.of("C:/loadtest/member-ids.txt")),
                Map.entry("technicalFailureThresholdPercent", List.of("0.5"))
        ));

        assertEquals(300, request.bookingFeederOffset());
        assertEquals("C:/loadtest/member-ids.txt", request.memberIdsFile());
        assertEquals(0.5, request.technicalFailureThresholdPercent());
    }

    @Test
    void rejectsInvalidCoreCapacityOffset() {
        assertThrows(IllegalArgumentException.class, () -> LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("core-admission-capacity"),
                "bookingFeederOffset", List.of("-1")
        )));
    }
    @Test
    void usesExplicitFeederRowsForClosedCoreModel() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("core-active-users-closed"),
                "injectionMode", List.of("closed-core"),
                "users", List.of("300"),
                "bookingFeederRows", List.of("12000")
        ));

        assertEquals(300, request.estimatedVirtualUsers());
        assertEquals(12000, request.expectedBookingRowsPerNode());
    }

    @Test
    void estimatesAllFiveCoreSpikeSegments() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "injectionMode", List.of("spike"),
                "usersPerSecond", List.of("100"),
                "targetUsersPerSecond", List.of("2000"),
                "durationSeconds", List.of("60")
        ));

        assertEquals(136500, request.estimatedVirtualUsers());
}

}
