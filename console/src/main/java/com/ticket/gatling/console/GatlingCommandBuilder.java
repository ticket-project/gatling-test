package com.ticket.gatling.console;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GatlingCommandBuilder {

    public List<String> build(final LoadTestRequest request) {
        return build(request, null);
    }

    public List<String> build(final LoadTestRequest request, final Path gatlingReportDir) {
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
        command.add("-DbaseUrl=" + request.baseUrl());
        command.add("-DperformanceId=" + request.performanceId());
        command.add("-Dusers=" + request.users());
        command.add("-DdurationSeconds=" + request.durationSeconds());
        command.add("-DinjectionMode=" + request.injectionMode());
        command.add("-DusersPerSecond=" + request.usersPerSecond());
        command.add("-DtargetUsersPerSecond=" + request.targetUsersPerSecond());
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
            } else if (!request.accessTokens().isBlank()) {
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
