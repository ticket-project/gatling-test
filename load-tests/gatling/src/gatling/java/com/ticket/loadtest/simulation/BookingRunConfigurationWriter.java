package com.ticket.loadtest.simulation;

import com.ticket.loadtest.LoadTestConfig;
import com.ticket.loadtest.RealisticSeatSelection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;

final class BookingRunConfigurationWriter {
    private static final Set<String> REALISTIC_SCENARIOS = Set.of(
            "CORE_ADMISSION_CAPACITY",
            "CORE_ACTIVE_USERS_CLOSED",
            "CORE_SPIKE"
    );
    private static final Set<String> QUEUE_SCENARIOS = Set.of(
            "TICKET_OPEN_END_TO_END",
            "QUEUE_PROTECTS_CORE"
    );

    private BookingRunConfigurationWriter() {
    }

    static void write(final Path resultFile, final String scenario) {
        final Path parent = Objects.requireNonNullElse(resultFile.toAbsolutePath().getParent(), Path.of("."));
        final Path output = parent.resolve("booking-run-config.json");
        final boolean admissionTokenIncluded = !"CORE_ADMISSION_CAPACITY".equals(scenario);
        final String queueBaseUrl = QUEUE_SCENARIOS.contains(scenario) ? LoadTestConfig.queueBaseUrl() : "";
        final String json = "{\n"
                + "  \"schemaVersion\":1,\n"
                + "  \"runId\":\"" + json(LoadTestConfig.consoleRunId()) + "\",\n"
                + "  \"scenario\":\"" + json(scenario) + "\",\n"
                + "  \"nodeIndex\":" + LoadTestConfig.nodeIndex() + ",\n"
                + "  \"coreBaseUrl\":\"" + json(LoadTestConfig.coreBaseUrl()) + "\",\n"
                + "  \"queueBaseUrl\":\"" + json(queueBaseUrl) + "\",\n"
                + "  \"performanceId\":\"" + json(LoadTestConfig.performanceId()) + "\",\n"
                + "  \"injection\":{"
                + "\"mode\":\"" + json(LoadTestConfig.injectionMode()) + "\","
                + "\"users\":" + LoadTestConfig.users() + ","
                + "\"durationSeconds\":" + LoadTestConfig.durationSeconds() + ","
                + "\"usersPerSecond\":" + LoadTestConfig.usersPerSecond() + ","
                + "\"targetUsersPerSecond\":" + LoadTestConfig.targetUsersPerSecond() + ","
                + "\"expectedUsers\":" + LoadTestConfig.expectedUsers() + "},\n"
                + "  \"authentication\":{"
                + "\"accessTokenMode\":\"" + json(LoadTestConfig.accessTokenMode()) + "\","
                + "\"admissionTokenIncluded\":" + admissionTokenIncluded + "},\n"
                + "  \"realisticUserModelApplied\":" + REALISTIC_SCENARIOS.contains(scenario) + ",\n"
                + "  \"behavior\":{"
                + "\"seatThinkMinMillis\":" + LoadTestConfig.bookingSeatThinkMin().toMillis() + ","
                + "\"seatThinkMaxMillis\":" + LoadTestConfig.bookingSeatThinkMax().toMillis() + ","
                + "\"orderThinkMinMillis\":" + LoadTestConfig.bookingOrderThinkMin().toMillis() + ","
                + "\"orderThinkMaxMillis\":" + LoadTestConfig.bookingOrderThinkMax().toMillis() + ","
                + "\"retryThinkMinMillis\":" + LoadTestConfig.bookingRetryThinkMin().toMillis() + ","
                + "\"retryThinkMaxMillis\":" + LoadTestConfig.bookingRetryThinkMax().toMillis() + ","
                + "\"seatRefreshPercent\":" + LoadTestConfig.bookingSeatRefreshPercent() + ","
                + "\"dropoutPercent\":" + LoadTestConfig.bookingDropoutPercent() + ","
                + "\"popularSeatPoolPercent\":" + RealisticSeatSelection.popularSeatPoolPercent() + ","
                + "\"popularSeatSelectionPercent\":" + RealisticSeatSelection.popularSeatSelectionPercent() + ","
                + "\"maxSeatSelectionAttempts\":" + RealisticSeatSelection.maxDynamicAttempts() + "},\n"
                + "  \"thresholds\":{"
                + "\"technicalFailurePercent\":" + LoadTestConfig.technicalFailureThresholdPercent() + ","
                + "\"performanceSummaryP95Millis\":" + LoadTestConfig.performanceSummaryP95ThresholdMs() + ","
                + "\"performanceSummaryP99Millis\":" + LoadTestConfig.performanceSummaryP99ThresholdMs() + ","
                + "\"seatStatusP95Millis\":" + LoadTestConfig.seatStatusP95ThresholdMs() + ","
                + "\"seatStatusP99Millis\":" + LoadTestConfig.seatStatusP99ThresholdMs() + ","
                + "\"seatSelectP95Millis\":" + LoadTestConfig.seatSelectP95ThresholdMs() + ","
                + "\"seatSelectP99Millis\":" + LoadTestConfig.seatSelectP99ThresholdMs() + ","
                + "\"orderCreateP95Millis\":" + LoadTestConfig.orderCreateP95ThresholdMs() + ","
                + "\"orderCreateP99Millis\":" + LoadTestConfig.orderCreateP99ThresholdMs() + ","
                + "\"orderGetP95Millis\":" + LoadTestConfig.orderGetP95ThresholdMs() + ","
                + "\"orderGetP99Millis\":" + LoadTestConfig.orderGetP99ThresholdMs() + ","
                + "\"queueTimeoutPercent\":" + LoadTestConfig.queueTimeoutThresholdPercent() + ","
                + "\"maxCoreAdmissionsPerSecond\":" + LoadTestConfig.maxCoreAdmissionsPerSecond() + ","
                + "\"admissionRateTolerancePercent\":" + LoadTestConfig.admissionRateTolerancePercent() + "},\n"
                + "  \"dbAuditEnabled\":" + LoadTestConfig.dbAuditEnabled() + "\n"
                + "}\n";
        try {
            Files.createDirectories(parent);
            Files.writeString(output, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write booking run configuration beside " + resultFile, exception);
        }
    }

    private static String json(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
