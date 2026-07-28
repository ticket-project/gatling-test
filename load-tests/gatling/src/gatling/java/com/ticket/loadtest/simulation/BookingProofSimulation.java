package com.ticket.loadtest.simulation;

import com.ticket.loadtest.BookingDatabaseAuditor;
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
    }

    @Override
    public void after() {
        final Path resultFile = Path.of(LoadTestConfig.resultFile());
        RuntimeException failure = null;
        try {
            BookingEvidenceRecorder.verifyAndWrite(
                    resultFile,
                    scenario,
                    LoadTestConfig.nodeIndex(),
                    LoadTestConfig.queueTimeoutThresholdPercent(),
                    LoadTestConfig.maxCoreAdmissionsPerSecond(),
                    LoadTestConfig.admissionRateTolerancePercent()
            );
        } catch (RuntimeException exception) {
            failure = exception;
        }

        if (LoadTestConfig.dbAuditEnabled()) {
            try {
                BookingDatabaseAuditor.audit(
                        resultFile,
                        Long.parseLong(LoadTestConfig.performanceId()),
                        resultFile.toAbsolutePath().resolveSibling("booking-db-audit.json")
                );
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}