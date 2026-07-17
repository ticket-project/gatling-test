package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatlingCommandBuilderTest {

    @Test
    void buildsBookingCapacityCommandFromFeederContract() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-gatling-load-tests"),
                "simulation", List.of("booking-capacity"),
                "coreBaseUrl", List.of("https://api.example.com"),
                "queueBaseUrl", List.of("https://queue.example.com"),
                "bookingFeederFile", List.of("C:/feeders/booking.csv"),
                "resultFile", List.of("build/results/booking.csv"),
                "pollingTimeoutSeconds", List.of("300")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request);

        assertTrue(command.contains("com.ticket.loadtest.simulation.BookingCapacitySimulation"));
        assertTrue(command.contains("-DcoreBaseUrl=https://api.example.com"));
        assertTrue(command.contains("-DqueueBaseUrl=https://queue.example.com"));
        assertTrue(command.contains("-DbookingFeederFile=C:/feeders/booking.csv"));
        assertTrue(command.contains("-DbookingScenario=BOOKING_CAPACITY"));
        assertTrue(command.contains("-DresultFile=build/results/booking.csv"));
        assertTrue(command.contains("-DpollingTimeoutSeconds=300"));
        assertFalse(command.stream().anyMatch(value -> value.startsWith("-DadmissionTokenSecret=")));
    }

    @Test
    void buildsSeatContentionCommandWithoutTokenSecrets() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket-gatling-load-tests"),
                "simulation", List.of("seat-contention"),
                "coreBaseUrl", List.of("https://api.example.com"),
                "bookingFeederFile", List.of("C:/feeders/contention.csv")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request);

        assertTrue(command.contains("com.ticket.loadtest.simulation.SeatContentionSimulation"));
        assertTrue(command.contains("-DbookingScenario=SEAT_CONTENTION"));
        assertTrue(command.contains("-DbookingFeederFile=C:/feeders/contention.csv"));
        assertFalse(command.stream().anyMatch(value -> value.startsWith("-DadmissionTokens=")));
        assertFalse(command.stream().anyMatch(value -> value.startsWith("-DadmissionTokenSecret=")));
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
    void includesPollingTimeoutInTicketOpenEndToEndCommand() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of("C:/ticket"),
                "simulation", List.of("ticket-open-end-to-end"),
                "coreBaseUrl", List.of("https://api.example.com"),
                "queueBaseUrl", List.of("https://queue.example.com"),
                "pollingTimeoutSeconds", List.of("240"),
                "statusPollPauseJitterSeconds", List.of("2")
        ));

        final List<String> command = new GatlingCommandBuilder().build(request);

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
                Path.of("C:/ticket/load-tests/gatling/build/tmp/gatling-console-runs/run-1")
        );

        assertTrue(command.contains("-DgatlingReportDir=C:\\ticket\\load-tests\\gatling\\build\\tmp\\gatling-console-runs\\run-1")
                || command.contains("-DgatlingReportDir=C:/ticket/load-tests/gatling/build/tmp/gatling-console-runs/run-1"));
    }
}
