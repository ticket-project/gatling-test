package com.ticket.gatling.console;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GatlingCommandBuilder {

    public List<String> build(
            final LoadTestRequest request,
            final Path gatlingReportDir,
            final String runDescription
    ) {
        final List<String> command = new ArrayList<>();
        command.add(request.gradleWrapper());
        command.add("-p");
        command.add("load-tests/gatling");
        command.add("gatlingRun");
        if (gatlingReportDir != null) {
            command.add("-DgatlingReportDir=" + gatlingReportDir.toAbsolutePath().normalize());
        }
        command.add("--simulation");
        command.add(request.simulationType().className());
        if (runDescription != null && !runDescription.isBlank()) {
            command.add("--run-description");
            command.add(runDescription);
        }
        if (request.simulationType().usesCoreBookingFlow()) {
            command.add("-DcoreBaseUrl=" + request.coreBaseUrl());
            if (request.simulationType().usesQueueBaseUrl()) {
                command.add("-DqueueBaseUrl=" + request.queueBaseUrl());
            }
            if (request.simulationType().usesBookingFeeder()) {
                command.add("-DbookingFeederFile=" + request.bookingFeederFile());
                command.add("-DbookingFeederOffset=" + request.bookingFeederOffset());
                if (request.closedBookingModel()) {
                    command.add("-DbookingFeederRows=" + request.bookingFeederRows());
                }
            }
            command.add("-DbookingScenario=" + request.simulationType().bookingScenario());
            command.add("-DresultFile=" + request.resultFile());
            command.add("-DtechnicalFailureThresholdPercent=" + request.technicalFailureThresholdPercent());
            command.add("-DpollingTimeoutSeconds=" + request.pollingTimeoutSeconds());
            command.add("-DqueueTimeoutThresholdPercent=" + request.queueTimeoutThresholdPercent());
            command.add("-DmaxCoreAdmissionsPerSecond=" + request.maxCoreAdmissionsPerSecond());
            command.add("-DadmissionRateTolerancePercent=" + request.admissionRateTolerancePercent());
        } else {
            command.add("-DbaseUrl=" + request.baseUrl());
        }
        command.add("-DperformanceId=" + request.performanceId());
        command.add("-Dusers=" + request.users());
        command.add("-DdurationSeconds=" + request.durationSeconds());
        command.add("-DinjectionMode=" + request.injectionMode());
        command.add("-DusersPerSecond=" + request.usersPerSecond());
        command.add("-DtargetUsersPerSecond=" + request.targetUsersPerSecond());
        if (request.http2Enabled()) {
            command.add("-Dhttp2Enabled=true");
        }
        if (request.simulationType().usesAccessTokens()
                && !request.simulationType().usesFeederAccessTokens()) {
            command.add("-DaccessTokenMode=" + request.accessTokenMode());

            if ("synthetic-jwt".equals(request.accessTokenMode())) {
                command.add("-DjwtSecret=" + request.jwtSecret());
                command.add("-DjwtIssuer=" + request.jwtIssuer());
                command.add("-DsyntheticMemberStartId=" + request.syntheticMemberStartId());
                command.add("-DsyntheticJwtRole=" + request.syntheticJwtRole());
                command.add("-DsyntheticTokenTtlSeconds=" + request.syntheticTokenTtlSeconds());
            } else if (request.usesAccessTokensFile()) {
                command.add("-DaccessTokensFile=" + request.accessTokensFile());
            } else if (request.usesInlineAccessTokens()) {
                command.add("-DaccessTokens=" + request.accessTokens());
            }
        }
        if (request.simulationType().usesStatusPolling()) {
            command.add("-DstatusPolls=" + request.statusPolls());
            command.add("-DstatusPollPauseSeconds=" + request.statusPollPauseSeconds());
            command.add("-DstatusPollPauseJitterSeconds=" + request.statusPollPauseJitterSeconds());
        }
        return List.copyOf(command);
    }

    public List<String> buildReport(
            final LoadTestRequest request,
            final Path gatlingReportDir,
            final String reportDirectoryName
    ) {
        return List.of(
                request.gradleWrapper(),
                "-p",
                "load-tests/gatling",
                "gatlingReport",
                "-DgatlingReportDir=" + gatlingReportDir.toAbsolutePath().normalize(),
                "-DgatlingReportName=" + reportDirectoryName
        );
    }
}
