package com.ticket.loadtest.simulation;

import com.ticket.loadtest.BookingEvidenceRecorder;
import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.Simulation;

import java.nio.file.Path;

abstract class BookingProofSimulation extends Simulation {
    private final String scenario;

    protected BookingProofSimulation(final String scenario) {
        this.scenario = scenario;
        BookingEvidenceRecorder.begin(
                Path.of(LoadTestConfig.resultFile()),
                scenario,
                LoadTestConfig.nodeIndex()
        );
        BookingRunConfigurationWriter.write(Path.of(LoadTestConfig.resultFile()), scenario);
    }

    @Override
    public void after() {
        final Path resultFile = Path.of(LoadTestConfig.resultFile());
        RuntimeException failure = null;
        BookingEvidenceRecorder.EvidenceSummary summary = null;
        try {
            summary = BookingEvidenceRecorder.verifyAndWrite(
                    resultFile,
                    scenario,
                    LoadTestConfig.nodeIndex(),
                    queueTimeoutThresholdPercent(),
                    maxCoreAdmissionsPerSecond(),
                    admissionRateTolerancePercent(),
                    LoadTestConfig.technicalFailureThresholdPercent()
            );
        } catch (RuntimeException exception) {
            failure = exception;
        }
        if (summary != null
                && "CORE_ADMISSION_CAPACITY".equals(scenario)
                && summary.startedUsers() != LoadTestConfig.expectedUsers()) {
            failure = new IllegalStateException("Core admission capacity started-user count mismatch: expected="
                    + LoadTestConfig.expectedUsers() + ", actual=" + summary.startedUsers());
        }

        if (failure != null) {
            throw failure;
        }
    }

    private double queueTimeoutThresholdPercent() {
        return usesQueueFlow() ? LoadTestConfig.queueTimeoutThresholdPercent() : 0.0;
    }

    private int maxCoreAdmissionsPerSecond() {
        return "QUEUE_PROTECTS_CORE".equals(scenario) ? LoadTestConfig.maxCoreAdmissionsPerSecond() : 0;
    }

    private double admissionRateTolerancePercent() {
        return "QUEUE_PROTECTS_CORE".equals(scenario)
                ? LoadTestConfig.admissionRateTolerancePercent()
                : 0.0;
    }

    private boolean usesQueueFlow() {
        return "TICKET_OPEN_END_TO_END".equals(scenario)
                || "QUEUE_PROTECTS_CORE".equals(scenario);
    }
}
