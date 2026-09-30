package com.ticket.loadtest.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
            "CORE_REALISTIC_CONTENTION",
            "CORE_REALISTIC_USER_MIX",
            "CORE_ACTIVE_USERS_CLOSED",
            "CORE_SPIKE"
    );
    private static final Set<String> ADMISSION_FREE_SCENARIOS = Set.of(
            "CORE_ADMISSION_CAPACITY",
            "CORE_REALISTIC_CONTENTION",
            "CORE_REALISTIC_USER_MIX"
    );
    private static final Set<String> QUEUE_SCENARIOS = Set.of(
            "TICKET_OPEN_END_TO_END",
            "QUEUE_PROTECTS_CORE"
    );
    private static final Set<String> NO_FEEDER_SCENARIOS = Set.of("CORE_REALISTIC_CONTENTION", "CORE_REALISTIC_USER_MIX");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BookingRunConfigurationWriter() {
    }

    static void write(final Path resultFile, final String scenario) {
        final Path parent = Objects.requireNonNullElse(resultFile.toAbsolutePath().getParent(), Path.of("."));
        final Path output = parent.resolve("booking-run-config.json");
        final boolean admissionTokenIncluded = !ADMISSION_FREE_SCENARIOS.contains(scenario);
        final String queueBaseUrl = QUEUE_SCENARIOS.contains(scenario) ? LoadTestConfig.queueBaseUrl() : "";
        final int feederRows = feederRows(scenario);
        final int feederOffset = feederRows == 0 ? 0 : LoadTestConfig.bookingFeederOffset();
        final long feederEndInclusive = feederRows == 0 ? -1L : (long) feederOffset + feederRows - 1L;
        final ObjectNode root = MAPPER.createObjectNode()
                .put("schemaVersion", 1)
                .put("runId", LoadTestConfig.consoleRunId())
                .put("scenario", scenario)
                .put("nodeIndex", LoadTestConfig.nodeIndex())
                .put("coreBaseUrl", LoadTestConfig.coreBaseUrl())
                .put("queueBaseUrl", queueBaseUrl)
                .put("performanceId", LoadTestConfig.performanceId());
        root.putObject("injection")
                .put("mode", LoadTestConfig.injectionMode())
                .put("users", LoadTestConfig.users())
                .put("durationSeconds", LoadTestConfig.durationSeconds())
                .put("usersPerSecond", LoadTestConfig.usersPerSecond())
                .put("targetUsersPerSecond", LoadTestConfig.targetUsersPerSecond())
                .put("expectedUsers", LoadTestConfig.expectedUsers());
        root.putObject("feeder")
                .put("file", feederRows == 0 ? "" : LoadTestConfig.bookingFeederFile())
                .put("offset", feederOffset)
                .put("requiredRows", feederRows)
                .put("rowStartInclusive", feederOffset)
                .put("rowEndInclusive", feederEndInclusive);
        root.putObject("authentication")
                .put("accessTokenMode", LoadTestConfig.accessTokenMode())
                .put("admissionTokenIncluded", admissionTokenIncluded);
        root.put("realisticUserModelApplied", REALISTIC_SCENARIOS.contains(scenario));
        root.putObject("behavior")
                .put("seatThinkMinMillis", LoadTestConfig.bookingSeatThinkMin().toMillis())
                .put("seatThinkMaxMillis", LoadTestConfig.bookingSeatThinkMax().toMillis())
                .put("orderThinkMinMillis", LoadTestConfig.bookingOrderThinkMin().toMillis())
                .put("orderThinkMaxMillis", LoadTestConfig.bookingOrderThinkMax().toMillis())
                .put("retryThinkMinMillis", LoadTestConfig.bookingRetryThinkMin().toMillis())
                .put("retryThinkMaxMillis", LoadTestConfig.bookingRetryThinkMax().toMillis())
                .put("seatRefreshPercent", LoadTestConfig.bookingSeatRefreshPercent())
                .put("dropoutPercent", LoadTestConfig.bookingDropoutPercent())
                .put("orderCancelPercent", LoadTestConfig.bookingOrderCancelPercent())
                .put("popularSeatPoolPercent", RealisticSeatSelection.popularSeatPoolPercent())
                .put("popularSeatSelectionPercent", RealisticSeatSelection.popularSeatSelectionPercent())
                .put("maxSeatSelectionAttempts", RealisticSeatSelection.maxDynamicAttempts());
        root.putObject("thresholds")
                .put("technicalFailurePercent", LoadTestConfig.technicalFailureThresholdPercent())
                .put("queueTimeoutPercent", LoadTestConfig.queueTimeoutThresholdPercent())
                .put("maxCoreAdmissionsPerSecond", LoadTestConfig.maxCoreAdmissionsPerSecond())
                .put("admissionRateTolerancePercent", LoadTestConfig.admissionRateTolerancePercent());
        root.put("dbAuditEnabled", LoadTestConfig.dbAuditEnabled());
        try {
            Files.createDirectories(parent);
            Files.writeString(output, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write booking run configuration beside " + resultFile, exception);
        }
    }

    private static int feederRows(final String scenario) {
        if (NO_FEEDER_SCENARIOS.contains(scenario)) {
            return 0;
        }
        return "CORE_ACTIVE_USERS_CLOSED".equals(scenario)
                ? LoadTestConfig.bookingFeederRows()
                : LoadTestConfig.expectedUsers();
    }
}
