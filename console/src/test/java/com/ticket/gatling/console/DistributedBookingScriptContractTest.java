package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributedBookingScriptContractTest {

    private static final Path SCRIPT = Path.of("../run-distributed-booking.ps1");

    @Test
    void exposesBookingDistributedExecutionInputs() throws IOException {
        final String source = source();

        assertTrue(source.contains("[string[]]$Hosts"));
        assertTrue(source.contains("[string]$KeyPath"));
        assertTrue(source.contains("[string]$RemoteProjectDir"));
        assertTrue(source.contains("[string]$Simulation"));
        assertTrue(source.contains("[string]$CoreBaseUrl"));
        assertTrue(source.contains("[string]$QueueBaseUrl"));
        assertTrue(source.contains("[int]$PerformanceId"));
        assertTrue(source.contains("[string]$FeederFile"));
        assertTrue(source.contains("[int]$RpsPerNode"));
        assertTrue(source.contains("[int]$DurationSeconds"));
        assertTrue(source.contains("[string]$InjectionMode"));
        assertTrue(source.contains("[int]$PollingTimeoutSeconds"));
        assertTrue(source.contains("[switch]$CollectReports"));
        assertTrue(source.contains("[string]$RunDescription"));
        assertTrue(source.contains("--run-description"));
    }

    @Test
    void validatesAndSplitsFourColumnFeederWithManifest() throws IOException {
        final String source = source();

        assertTrue(source.contains("memberId,accessToken,seatId,admissionToken"));
        assertTrue(source.contains("FeederFile must be UTF-8 without BOM"));
        assertTrue(source.contains("$rowsPerNode = Get-ExpectedUsersPerNode"));
        assertTrue(source.contains("default { [Math]::Ceiling($RpsPerNode * $DurationSeconds) }"));
        assertTrue(source.contains("required=$requiredRows"));
        assertTrue(source.contains("nodeIndex"));
        assertTrue(source.contains("totalNodes"));
        assertTrue(source.contains("globalRps"));
        assertTrue(source.contains("nodeRps"));
        assertTrue(source.contains("rowStart"));
        assertTrue(source.contains("rowEnd"));
        assertTrue(source.contains("exit 2"));
    }

    @Test
    void passesOnlyOperationalBookingPropertiesToRemoteGatling() throws IOException {
        final String source = source();

        assertTrue(source.contains("-DcoreBaseUrl=$CoreBaseUrl"));
        assertTrue(source.contains("-DqueueBaseUrl=$QueueBaseUrl"));
        assertTrue(source.contains("-DbookingFeederFile=$RemoteFeederFile"));
        assertTrue(source.contains("-DbookingScenario=$(Get-BookingScenario)"));
        assertTrue(source.contains("-DnodeIndex=$NodeIndex"));
        assertTrue(source.contains("-DresultFile=$RemoteResultFile"));
        assertTrue(source.contains("-DpollingTimeoutSeconds=$PollingTimeoutSeconds"));
        assertFalse(source.contains("JwtSecret"));
        assertFalse(source.contains("AdmissionTokenSecret"));
    }

    @Test
    void runsRemoteNodesConcurrentlyAndCollectsResults() throws IOException {
        final String source = source();

        assertTrue(source.contains("Start-Job -Name $safeName"));
        assertTrue(source.contains("Wait-Job $jobs"));
        assertTrue(source.contains("booking-results.csv"));
        assertTrue(source.contains("booking-results-merged.csv"));
        assertTrue(source.contains("booking-summary.json"));
        assertTrue(source.contains("duplicateSuccessfulSeats"));
        assertTrue(source.contains("duplicateOrderKeys"));
        assertTrue(source.contains("technicalFailurePercent"));
        assertTrue(source.contains("TechnicalFailureThresholdPercent"));
    }

    private static String source() throws IOException {
        return Files.readString(SCRIPT, StandardCharsets.UTF_8);
    }
}