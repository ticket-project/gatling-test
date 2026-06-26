package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatlingCommandBuilderTest {

    @Test
    void buildsTicketServerCapacityCommandWithSyntheticAdmissionTokenDefaults() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-gatling-load-tests"),
                "simulation", List.of("ticket-server-capacity"),
                "seatIds", List.of("1,2,3"),
                "admissionTokenMode", List.of("synthetic"),
                "admissionTokenIssuer", List.of("ticket-queue"),
                "admissionTokenAudience", List.of("ticket-api"),
                "admissionTokenSecret", List.of("0123456789abcdef0123456789abcdef"),
                "admissionTokenTtlSeconds", List.of("300")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request);

        assertTrue(command.contains("com.ticket.loadtest.simulation.TicketServerCapacitySimulation"));
        assertTrue(command.contains("-DseatIds=1,2,3"));
        assertTrue(command.contains("-DadmissionTokenMode=synthetic"));
        assertTrue(command.contains("-DadmissionTokenIssuer=ticket-queue"));
        assertTrue(command.contains("-DadmissionTokenAudience=ticket-api"));
        assertTrue(command.contains("-DadmissionTokenSecret=0123456789abcdef0123456789abcdef"));
        assertTrue(command.contains("-DadmissionTokenTtlSeconds=300"));
    }

    @Test
    void passesManualAdmissionTokensWhenConfigured() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-gatling-load-tests"),
                "simulation", List.of("hold-race"),
                "admissionTokenMode", List.of("tokens"),
                "admissionTokens", List.of("adm-1,adm-2")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request);

        assertTrue(command.contains("-DadmissionTokenMode=tokens"));
        assertTrue(command.contains("-DadmissionTokens=adm-1,adm-2"));
    }

    @Test
    void buildsLegacyQueueStatusCommandWithPollingOptions() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-gatling-load-tests"),
                "simulation", List.of("legacy-queue-status"),
                "baseUrl", List.of("http://localhost:8090"),
                "performanceId", List.of("13669679"),
                "statusPolls", List.of("20"),
                "statusPollPauseSeconds", List.of("5"),
                "statusPollPauseJitterSeconds", List.of("2")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request);

        assertTrue(command.contains("com.ticket.loadtest.simulation.LegacyQueueStatusSimulation"));
        assertTrue(command.contains("-DbaseUrl=http://localhost:8090"));
        assertTrue(command.contains("-DperformanceId=13669679"));
        assertTrue(command.contains("-DstatusPolls=20"));
        assertTrue(command.contains("-DstatusPollPauseSeconds=5"));
        assertTrue(command.contains("-DstatusPollPauseJitterSeconds=2"));
    }

    @Test
    void buildsQueueJoinOnlyCommandWithoutEnterOrPollingOptions() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-gatling-load-tests"),
                "simulation", List.of("queue-join-only"),
                "performanceId", List.of("13669679"),
                "accessTokenMode", List.of("synthetic-jwt")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request);

        assertTrue(command.contains("com.ticket.loadtest.simulation.QueueJoinOnlySimulation"));
        assertTrue(command.contains("-DbaseUrl=https://queue.oneticket.site"));
        assertTrue(command.contains("-DperformanceId=13669679"));
        assertTrue(command.contains("-DaccessTokenMode=synthetic-jwt"));
        assertFalse(command.contains("-DstatusPolls=3"));
        assertFalse(command.contains("-DseatIds=1"));
        assertFalse(command.contains("-DadmissionTokenMode=synthetic"));
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

        final List<String> command = new GatlingCommandBuilder().build(request);

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

        final List<String> command = new GatlingCommandBuilder().build(request);

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

        final List<String> command = new GatlingCommandBuilder().build(request);

        assertTrue(command.contains("com.ticket.loadtest.simulation.CdnPublicStateSimulation"));
        assertTrue(command.contains("-DbaseUrl=https://queue.example.com"));
        assertTrue(command.contains("-DperformanceId=13669679"));
        assertTrue(command.contains("-DstatusPolls=20"));
        assertTrue(command.contains("-DstatusPollPauseSeconds=5"));
        assertTrue(command.contains("-DstatusPollPauseJitterSeconds=2"));
    }

    @Test
    void includesJitterInTicketOpenFlowCommand() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket"),
                "simulation", List.of("ticket-open-flow"),
                "statusPollPauseJitterSeconds", List.of("2")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request);

        assertTrue(command.contains("com.ticket.loadtest.simulation.TicketOpenFlowSimulation"));
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
                Path.of("C:/ticket/load-tests/gatling/build/tmp/gatling-console-runs/run-1")
        );

        assertTrue(command.contains("-DgatlingReportDir=C:\\ticket\\load-tests\\gatling\\build\\tmp\\gatling-console-runs\\run-1")
                || command.contains("-DgatlingReportDir=C:/ticket/load-tests/gatling/build/tmp/gatling-console-runs/run-1"));
    }
}
