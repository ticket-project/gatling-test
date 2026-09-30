package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatlingCommandBuilderTest {

    @Test
    void buildsSmokeCommandFromFeederContract() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-gatling-load-tests"),
                "simulation", List.of("smoke"),
                "coreBaseUrl", List.of("https://api.example.com"),
                "queueBaseUrl", List.of("https://queue.example.com"),
                "bookingFeederFile", List.of("C:/feeders/booking.csv"),
                "resultFile", List.of("build/results/booking.csv"),
                "pollingTimeoutSeconds", List.of("300")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request, null, "");

        assertTrue(command.contains("com.ticket.loadtest.simulation.SmokeSimulation"));
        assertTrue(command.contains("-DcoreBaseUrl=https://api.example.com"));
        assertFalse(command.contains("-DqueueBaseUrl=https://queue.example.com"));
        assertTrue(command.contains("-DbookingFeederFile=C:/feeders/booking.csv"));
        assertTrue(command.contains("-DbookingScenario=SMOKE"));
        assertTrue(command.contains("-DresultFile=build/results/booking.csv"));
        assertTrue(command.contains("-DpollingTimeoutSeconds=300"));
        assertFalse(command.stream().anyMatch(value -> value.startsWith("-DadmissionToken")));
        assertFalse(command.stream().anyMatch(value -> value.startsWith("-DnodeIndex=")));
    }

    @Test
    void buildsQueueJoinOnlyCommandWithoutEnterOrPollingOptions() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-gatling-load-tests"),
                "simulation", List.of("queue-join-only"),
                "performanceId", List.of("13669679"),
                "accessTokenMode", List.of("synthetic-jwt")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request, null, "");

        assertTrue(command.contains("com.ticket.loadtest.simulation.QueueJoinOnlySimulation"));
        assertTrue(command.contains("-DbaseUrl="));
        assertFalse(command.stream().anyMatch(value -> value.contains("queue.oneticket.site")));
        assertTrue(command.contains("-DperformanceId=13669679"));
        assertTrue(command.contains("-DaccessTokenMode=synthetic-jwt"));
        assertFalse(command.contains("-DstatusPolls=3"));
    }

    @Test
    void passesAccessTokensFileBeforeInlineAccessTokens() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-gatling-load-tests"),
                "simulation", List.of("queue-join-only"),
                "accessTokenMode", List.of("tokens"),
                "accessTokens", List.of("inline-token"),
                "accessTokensFile", List.of("C:/tokens/access-tokens.txt")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request, null, "");

        assertTrue(command.contains("-DaccessTokensFile=C:/tokens/access-tokens.txt"));
        assertFalse(command.contains("-DaccessTokens=inline-token"));
    }

    @Test
    void passesGeneratedAccessTokensFileWithoutJwtGenerationArgumentsToGatlingRun() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-gatling-load-tests"),
                "simulation", List.of("queue-join-only"),
                "accessTokenMode", List.of("tokens"),
                "accessTokenSource", List.of("generate-file"),
                "accessTokensFile", List.of("C:/tokens/generated-access-tokens.txt")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request, null, "");

        assertTrue(command.contains("-DaccessTokensFile=C:/tokens/generated-access-tokens.txt"));
        assertFalse(command.stream().anyMatch(value -> value.startsWith("-DaccessTokens=")));
        assertFalse(command.stream().anyMatch(value -> value.startsWith("-DjwtSecret=")));
    }

    @Test
    void buildsCdnPublicStateCommandWithPollingOptions() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-gatling-load-tests"),
                "simulation", List.of("cdn-public-state"),
                "baseUrl", List.of("https://queue.example.com"),
                "performanceId", List.of("13669679"),
                "statusPolls", List.of("20"),
                "statusPollPauseSeconds", List.of("5"),
                "statusPollPauseJitterSeconds", List.of("2")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request, null, "");

        assertTrue(command.contains("com.ticket.loadtest.simulation.CdnPublicStateSimulation"));
        assertTrue(command.contains("-DbaseUrl=https://queue.example.com"));
        assertTrue(command.contains("-DperformanceId=13669679"));
        assertTrue(command.contains("-DstatusPolls=20"));
        assertTrue(command.contains("-DstatusPollPauseSeconds=5"));
        assertTrue(command.contains("-DstatusPollPauseJitterSeconds=2"));
    }

    @Test
    void includesPollingTimeoutInTicketOpenEndToEndCommand() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket"),
                "simulation", List.of("ticket-open-end-to-end"),
                "coreBaseUrl", List.of("https://api.example.com"),
                "queueBaseUrl", List.of("https://queue.example.com"),
                "pollingTimeoutSeconds", List.of("240"),
                "statusPollPauseJitterSeconds", List.of("2")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request, null, "");

        assertTrue(command.contains("com.ticket.loadtest.simulation.TicketOpenEndToEndSimulation"));
        assertTrue(command.contains("-DpollingTimeoutSeconds=240"));
        assertTrue(command.contains("-DstatusPollPauseJitterSeconds=2"));
    }

    @Test
    void canOverrideGatlingReportRootForConsoleManagedRuns() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket"),
                "simulation", List.of("cdn-public-state")
        ));

        final List<String> command = new GatlingCommandBuilder().build(
                request,
                Path.of("C:/ticket/load-tests/gatling/build/tmp/gatling-console-runs/run-1"),
                ""
        );

        assertTrue(command.contains("-DgatlingReportDir=C:\\ticket\\load-tests\\gatling\\build\\tmp\\gatling-console-runs\\run-1")
                || command.contains("-DgatlingReportDir=C:/ticket/load-tests/gatling/build/tmp/gatling-console-runs/run-1"));
    }

    @Test
    void buildsReportRecoveryCommandForExistingSimulationLog() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket"),
                "simulation", List.of("cdn-public-state")
        ));

        final List<String> command = new GatlingCommandBuilder().buildReport(
                request,
                Path.of("C:/reports"),
                "run-result-123"
        );

        assertTrue(command.contains("gatlingReport"));
        assertTrue(command.contains("-DgatlingReportDir=C:\\reports")
                || command.contains("-DgatlingReportDir=C:/reports"));
        assertTrue(command.contains("-DgatlingReportName=run-result-123"));
        assertFalse(command.contains("gatlingRun"));
    }

    @Test
    void includesRunDescriptionInGatlingCommand() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket"),
                "simulation", List.of("cdn-public-state")
        ));

        final List<String> command = new GatlingCommandBuilder().build(
                request,
                Path.of("C:/reports"),
                "runId=f8290000,commit=a91b32f"
        );

        assertTrue(command.contains("--run-description"));
        assertTrue(command.contains("runId=f8290000,commit=a91b32f"));
    }
    @Test
    void buildsClosedCoreCommandWithSeparateFeederCapacity() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket"),
                "simulation", List.of("core-active-users-closed"),
                "coreBaseUrl", List.of("https://api.example.com"),
                "bookingFeederFile", List.of("C:/feeders/closed.csv"),
                "bookingFeederRows", List.of("10000"),
                "users", List.of("300"),
                "injectionMode", List.of("closed-core")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request, null, "");

        assertTrue(command.contains("com.ticket.loadtest.simulation.CoreActiveUsersClosedSimulation"));
        assertTrue(command.contains("-DbookingFeederRows=10000"));
    }

    @Test
    void passesCoreCapacityOffsetAndSlos() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.ofEntries(
                Map.entry("ticketProjectPath", List.of("C:/ticket")),
                Map.entry("simulation", List.of("core-admission-capacity")),
                Map.entry("coreBaseUrl", List.of("https://api.example.com")),
                Map.entry("bookingFeederFile", List.of("C:/feeders/capacity.csv")),
                Map.entry("bookingFeederOffset", List.of("300")),
                Map.entry("technicalFailureThresholdPercent", List.of("0.75"))
        ));

        final List<String> command = new GatlingCommandBuilder().build(
                request, Path.of("C:/reports"), "capacity"
        );

        assertTrue(command.contains("-DbookingFeederOffset=300"));
        assertTrue(command.contains("-DtechnicalFailureThresholdPercent=0.75"));
        assertTrue(command.stream().noneMatch(argument -> argument.contains("ThresholdMs")));
    }

    @Test
    void buildsOrderGetWithInMemorySyntheticJwtAndSafeFeeder() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.ofEntries(
                Map.entry("ticketProjectPath", List.of("C:/ticket")),
                Map.entry("simulation", List.of("core-order-get-api")),
                Map.entry("coreBaseUrl", List.of("https://api.example.com")),
                Map.entry("bookingFeederFile", List.of("C:/feeders/order-lookup.csv")),
                Map.entry("accessTokenMode", List.of("synthetic-jwt")),
                Map.entry("jwtSecret", List.of("0123456789abcdef0123456789abcdef"))
        ));

        final List<String> command = new GatlingCommandBuilder().build(request, null, "");

        assertTrue(command.contains("-DbookingFeederFile=C:/feeders/order-lookup.csv"));
        assertTrue(command.contains("-DaccessTokenMode=synthetic-jwt"));
        assertTrue(command.contains("-DjwtSecret=0123456789abcdef0123456789abcdef"));
        assertFalse(command.stream().anyMatch(value -> value.startsWith("-DaccessTokensFile=")));
    }
}
