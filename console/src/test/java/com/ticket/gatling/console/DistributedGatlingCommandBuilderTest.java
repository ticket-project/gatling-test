package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributedGatlingCommandBuilderTest {
    private static final UUID RUN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void buildsCdnDistributedScriptCommandWithConfiguredHosts() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.ofEntries(
                Map.entry("ticketProjectPath", List.of("C:/ticket-workspace/gatling-test")),
                Map.entry("executionMode", List.of("distributed")),
                Map.entry("simulation", List.of("cdn-public-state")),
                Map.entry("baseUrl", List.of("https://queue.example.com")),
                Map.entry("performanceId", List.of("7")),
                Map.entry("usersPerSecond", List.of("500")),
                Map.entry("durationSeconds", List.of("100")),
                Map.entry("statusPolls", List.of("1")),
                Map.entry("statusPollPauseSeconds", List.of("0")),
                Map.entry("statusPollPauseJitterSeconds", List.of("0")),
                Map.entry("distributedHosts", List.of("43.203.155.15\nubuntu@15.165.40.25")),
                Map.entry("distributedCollectReports", List.of("on")),
                Map.entry("distributedDumpFailureBody", List.of("on")),
                Map.entry("distributedDumpFailureBodyLimit", List.of("2"))
        ));

        final List<String> command = new DistributedGatlingCommandBuilder().build(request, RUN_ID);

        assertTrue(command.getFirst().toLowerCase(java.util.Locale.ROOT).contains("powershell"));
        assertTrue(command.contains("-ConsoleRunId"));
        assertTrue(command.contains(RUN_ID.toString()));
        assertTrue(command.stream().anyMatch(value -> value.endsWith("run-distributed-gatling-cdn.ps1")));
        assertTrue(command.contains("-Hosts"));
        assertTrue(command.contains("ubuntu@43.203.155.15,ubuntu@15.165.40.25"));
        assertTrue(command.contains("-RpsPerNode"));
        assertTrue(command.contains("500"));
        assertTrue(command.contains("-DurationSeconds"));
        assertTrue(command.contains("100"));
        assertTrue(command.contains("-BaseUrl"));
        assertTrue(command.contains("https://queue.example.com"));
        assertTrue(command.contains("-PerformanceId"));
        assertTrue(command.contains("7"));
        assertTrue(command.contains("-StatusPolls"));
        assertTrue(command.contains("1"));
        assertTrue(command.contains("-DumpFailureBody"));
        assertTrue(command.contains("-DumpFailureBodyLimit"));
        assertTrue(command.contains("2"));
        assertTrue(command.contains("-CollectReports"));
        assertFalse(command.contains("-IncludeLocal"));
    }

    @Test
    void buildsLegacyDistributedScriptCommandWithLocalNode() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-workspace/gatling-test"),
                "executionMode", List.of("distributed"),
                "simulation", List.of("legacy-queue-status"),
                "usersPerSecond", List.of("300"),
                "durationSeconds", List.of("60"),
                "distributedHosts", List.of("43.203.136.184"),
                "distributedIncludeLocal", List.of("on")
        ));

        final List<String> command = new DistributedGatlingCommandBuilder().build(request, RUN_ID);

        assertTrue(command.stream().anyMatch(value -> value.endsWith("run-distributed-gatling-legacy.ps1")));
        assertTrue(command.contains("ubuntu@43.203.136.184"));
        assertTrue(command.contains("-RpsPerNode"));
        assertTrue(command.contains("300"));
        assertTrue(command.contains("-IncludeLocal"));
    }

    @Test
    void buildsQueueJoinDistributedScriptCommandWithGeneratedTokenFile() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-workspace/gatling-test"),
                "executionMode", List.of("distributed"),
                "simulation", List.of("queue-join-only"),
                "accessTokenMode", List.of("tokens"),
                "accessTokenSource", List.of("generate-file"),
                "usersPerSecond", List.of("300"),
                "durationSeconds", List.of("60"),
                "generatedAccessTokenCount", List.of("18000"),
                "jwtSecret", List.of("0123456789abcdef0123456789abcdef")
        ));

        final List<String> command = new DistributedGatlingCommandBuilder().build(request, RUN_ID);

        assertTrue(command.stream().anyMatch(value -> value.endsWith("run-distributed-gatling-join.ps1")));
        assertTrue(command.contains("-AccessTokenMode"));
        assertTrue(command.contains("-InjectionMode"));
        assertTrue(command.contains("tokens"));
        assertTrue(command.contains("-GenerateAccessTokens"));
        assertTrue(command.contains("-TokenCountPerNode"));
        assertTrue(command.contains("18000"));
        assertTrue(command.contains("-JwtSecret"));
    }
    @Test
    void buildsBookingDistributedScriptCommandWithoutSecrets() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.ofEntries(
                Map.entry("ticketProjectPath", List.of("C:/ticket-workspace/gatling-test")),
                Map.entry("executionMode", List.of("distributed")),
                Map.entry("simulation", List.of("ticket-open-end-to-end")),
                Map.entry("coreBaseUrl", List.of("https://api.example.com")),
                Map.entry("queueBaseUrl", List.of("https://queue.example.com")),
                Map.entry("bookingFeederFile", List.of("C:/feeders/booking.csv")),
                Map.entry("usersPerSecond", List.of("300")),
                Map.entry("durationSeconds", List.of("60")),
                Map.entry("pollingTimeoutSeconds", List.of("300")),
                Map.entry("distributedRemoteProjectDir", List.of("~/gatling-test-booking")),
                Map.entry("distributedHosts", List.of("43.203.155.15\nubuntu@15.165.40.25")),
                Map.entry("operationalConfirmation", List.of("on"))
        ));

        final List<String> command = new DistributedGatlingCommandBuilder().build(request, RUN_ID);

        assertTrue(command.stream().anyMatch(value -> value.endsWith("run-distributed-booking.ps1")));
        assertTrue(command.contains("-Simulation"));
        assertTrue(command.contains("com.ticket.loadtest.simulation.TicketOpenEndToEndSimulation"));
        assertTrue(command.contains("-CoreBaseUrl"));
        assertTrue(command.contains("https://api.example.com"));
        assertTrue(command.contains("-QueueBaseUrl"));
        assertTrue(command.contains("https://queue.example.com"));
        assertTrue(command.contains("-FeederFile"));
        assertTrue(command.contains("C:/feeders/booking.csv"));
        assertTrue(command.contains("-RemoteProjectDir"));
        assertTrue(command.contains("~/gatling-test-booking"));
        assertFalse(command.contains("-JwtSecret"));
        assertFalse(command.contains("-AdmissionTokenSecret"));
    }
    @Test
    void passesRunDescriptionToDistributedScript() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-workspace/gatling-test"),
                "executionMode", List.of("distributed"),
                "simulation", List.of("cdn-public-state")
        ));

        final List<String> command = new DistributedGatlingCommandBuilder().build(
                request,
                RUN_ID,
                "runId=f8290000,core=4vCPU/8GiB"
        );

        assertTrue(command.contains("-RunDescription"));
        assertTrue(command.contains("runId=f8290000,core=4vCPU/8GiB"));
    }
    @Test
    void buildsClosedCoreDistributedCommandWithConcurrencyAndFeederRows() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.ofEntries(
                Map.entry("ticketProjectPath", List.of("C:/ticket-workspace/gatling-test")),
                Map.entry("executionMode", List.of("distributed")),
                Map.entry("simulation", List.of("core-active-users-closed")),
                Map.entry("coreBaseUrl", List.of("https://api.example.com")),
                Map.entry("bookingFeederFile", List.of("C:/feeders/closed.csv")),
                Map.entry("bookingFeederRows", List.of("10000")),
                Map.entry("users", List.of("100")),
                Map.entry("injectionMode", List.of("closed-core"))
        ));

        final List<String> command = new DistributedGatlingCommandBuilder().build(request, RUN_ID);

        assertTrue(command.stream().anyMatch(value -> value.endsWith("run-distributed-booking.ps1")));
        assertTrue(command.contains("-ConcurrentUsersPerNode"));
        assertTrue(command.contains("100"));
        assertTrue(command.contains("-FeederRowsPerNode"));
        assertTrue(command.contains("10000"));
    }

    @Test
    void passesSpikeTargetRpsToDistributedRunner() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-workspace/gatling-test"),
                "executionMode", List.of("distributed"),
                "simulation", List.of("core-spike"),
                "injectionMode", List.of("spike"),
                "usersPerSecond", List.of("100"),
                "targetUsersPerSecond", List.of("2000")
        ));

        final List<String> command = new DistributedGatlingCommandBuilder().build(request, RUN_ID);

        assertTrue(command.contains("-TargetRpsPerNode"));
        assertTrue(command.contains("2000"));
    }

}
