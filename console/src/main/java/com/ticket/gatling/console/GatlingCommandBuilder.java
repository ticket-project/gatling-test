package com.ticket.gatling.console;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GatlingCommandBuilder {

    public List<String> build(final LoadTestRequest request) {
        return build(request, null, "");
    }

    public List<String> build(final LoadTestRequest request, final Path gatlingReportDir) {
        return build(request, gatlingReportDir, "");
    }

    public List<String> build(
            final LoadTestRequest request,
            final Path gatlingReportDir,
            final String runDescription
    ) {
        final List<String> command = new ArrayList<>();
        command.add(gradleWrapper(request));
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
                if (request.closedBookingModel()) {
                    command.add("-DbookingFeederRows=" + request.bookingFeederRows());
                }
            }
            command.add("-DbookingScenario=" + request.bookingScenario());
            command.add("-DnodeIndex=" + request.nodeIndex());
            command.add("-DresultFile=" + request.resultFile());
            command.add("-DpollingTimeoutSeconds=" + request.pollingTimeoutSeconds());
            command.add("-DqueueTimeoutThresholdPercent=" + request.queueTimeoutThresholdPercent());
            command.add("-DmaxCoreAdmissionsPerSecond=" + request.maxCoreAdmissionsPerSecond());
            command.add("-DadmissionRateTolerancePercent=" + request.admissionRateTolerancePercent());
            command.add("-DdbAuditEnabled=" + request.dbAuditEnabled());
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
        if (request.simulationType().usesAccessTokens()) {
            command.add("-DaccessTokenMode=" + request.accessTokenMode());
            command.add("-DloginEmailPrefix=" + request.loginEmailPrefix());
            command.add("-DloginEmailDomain=" + request.loginEmailDomain());
            command.add("-DloginPassword=" + request.loginPassword());
            command.add("-DloginStartIndex=" + request.loginStartIndex());
            command.add("-DloginTimeoutSeconds=" + request.loginTimeoutSeconds());

            if ("synthetic-jwt".equalsIgnoreCase(request.accessTokenMode())) {
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

        if (request.simulationType().usesSeatIds()) {
            command.add("-DseatIds=" + request.seatIds());
        }
        if (request.simulationType().usesStatusPolling()) {
            command.add("-DstatusPolls=" + request.statusPolls());
            command.add("-DstatusPollPauseSeconds=" + request.statusPollPauseSeconds());
            command.add("-DstatusPollPauseJitterSeconds=" + request.statusPollPauseJitterSeconds());
        }
        if (request.simulationType().usesAdmissionTokens()) {
            command.add("-DadmissionTokenMode=" + request.admissionTokenMode());
            if ("synthetic".equalsIgnoreCase(request.admissionTokenMode())) {
                command.add("-DadmissionTokenIssuer=" + request.admissionTokenIssuer());
                command.add("-DadmissionTokenAudience=" + request.admissionTokenAudience());
                command.add("-DadmissionTokenSecret=" + request.admissionTokenSecret());
                command.add("-DadmissionTokenTtlSeconds=" + request.admissionTokenTtlSeconds());
            } else if (!request.admissionTokens().isBlank()) {
                command.add("-DadmissionTokens=" + request.admissionTokens());
            }
        }
        return List.copyOf(command);
    }

    private String gradleWrapper(final LoadTestRequest request) {
        final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return request.ticketProjectPath().resolve(windows ? "gradlew.bat" : "gradlew").toString();
    }
}
