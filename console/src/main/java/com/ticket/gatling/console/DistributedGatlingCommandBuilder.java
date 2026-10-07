package com.ticket.gatling.console;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DistributedGatlingCommandBuilder {

    public List<String> build(
            final LoadTestRequest request,
            final UUID runId,
            final String runDescription
    ) {
        final boolean booking = request.simulationType().usesBookingFeeder();
        final List<String> command = new ArrayList<>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(scriptPath(request).toString());
        command.add("-ConsoleRunId");
        command.add(runId.toString());
        if (runDescription != null && !runDescription.isBlank()) {
            command.add("-RunDescription");
            command.add(runDescription);
        }
        command.add("-KeyPath");
        command.add(request.sshKeyPath().toString());
        command.add("-Hosts");
        command.add(String.join(",", request.distributedHostList()));
        command.add("-RemoteProjectDir");
        command.add(request.distributedRemoteProjectDir());
        command.add("-Simulation");
        command.add(request.simulationType().className());
        command.add("-RpsPerNode");
        final int loadPerNode = "at-once-users".equals(request.injectionMode())
                ? request.users() : (int) Math.ceil(request.usersPerSecond());
        command.add(String.valueOf(loadPerNode));
        command.add("-DurationSeconds");
        command.add(String.valueOf(request.durationSeconds()));
        command.add("-PerformanceId");
        command.add(request.performanceId());

        if (booking) {
            addBookingArguments(command, request);
            return List.copyOf(command);
        }
        addQueueArguments(command, request);
        if (request.distributedIncludeLocal()) {
            command.add("-IncludeLocal");
        }
        if (request.distributedDumpFailureBody()) {
            command.add("-DumpFailureBody");
            command.add("-DumpFailureBodyLimit");
            command.add(String.valueOf(request.distributedDumpFailureBodyLimit()));
        }
        if (request.distributedCollectReports() || request.distributedDumpFailureBody()) {
            command.add("-CollectReports");
        }
        return List.copyOf(command);
    }

    private void addBookingArguments(final List<String> command, final LoadTestRequest request) {
        command.add("-CoreBaseUrl");
        command.add(request.coreBaseUrl());
        command.add("-QueueBaseUrl");
        command.add(request.queueBaseUrl());
        command.add("-FeederFile");
        command.add(request.bookingFeederFile());
        command.add("-FeederOffset");
        command.add(String.valueOf(request.bookingFeederOffset()));
        command.add("-InjectionMode");
        command.add(request.injectionMode());
        command.add("-PollingTimeoutSeconds");
        command.add(String.valueOf(request.pollingTimeoutSeconds()));
        command.add("-TargetRpsPerNode");
        command.add(String.valueOf((int) Math.ceil(request.targetUsersPerSecond())));
        if (request.closedBookingModel()) {
            command.add("-ConcurrentUsersPerNode");
            command.add(String.valueOf(request.users()));
            command.add("-FeederRowsPerNode");
            command.add(String.valueOf(request.bookingFeederRows()));
        }
        command.add("-StatusPollPauseSeconds");
        command.add(String.valueOf(request.statusPollPauseSeconds()));
        command.add("-StatusPollPauseJitterSeconds");
        command.add(String.valueOf(request.statusPollPauseJitterSeconds()));
        command.add("-QueueTimeoutThresholdPercent");
        command.add(String.valueOf(request.queueTimeoutThresholdPercent()));
        command.add("-MaxCoreAdmissionsPerSecond");
        command.add(String.valueOf(request.maxCoreAdmissionsPerSecond()));
        command.add("-AdmissionRateTolerancePercent");
        command.add(String.valueOf(request.admissionRateTolerancePercent()));
        command.add("-TechnicalFailureThresholdPercent");
        command.add(String.valueOf(request.technicalFailureThresholdPercent()));
    }

    private void addQueueArguments(final List<String> command, final LoadTestRequest request) {
        command.add("-BaseUrl");
        command.add(request.baseUrl());
        if (request.simulationType() == SimulationType.QUEUE_JOIN_ONLY) {
            // 예전 join 래퍼 스크립트가 하던 일: 로컬 프로젝트를 VM에 동기화한다.
            command.add("-SyncProject");
            command.add("-InjectionMode");
            command.add(request.injectionMode());
        }
        command.add("-StatusPolls");
        command.add(String.valueOf(request.statusPolls()));
        command.add("-StatusPollPauseSeconds");
        command.add(String.valueOf(request.statusPollPauseSeconds()));
        command.add("-StatusPollPauseJitterSeconds");
        command.add(String.valueOf(request.statusPollPauseJitterSeconds()));

        if (request.http2Enabled()) {
            command.add("-EnableHttp2");
        }

        if (request.simulationType().usesAccessTokens()) {
            command.add("-AccessTokenMode");
            command.add(request.accessTokenMode());
            if ("synthetic-jwt".equals(request.accessTokenMode()) || request.generatesAccessTokensFile()) {
                command.add("-JwtSecret");
                command.add(request.jwtSecret());
                command.add("-JwtIssuer");
                command.add(request.jwtIssuer());
                command.add("-SyntheticMemberStartId");
                command.add(String.valueOf(request.syntheticMemberStartId()));
                command.add("-SyntheticJwtRole");
                command.add(request.syntheticJwtRole());
                command.add("-SyntheticTokenTtlSeconds");
                command.add(String.valueOf(request.syntheticTokenTtlSeconds()));
            }
            // 분산 join은 synthetic-jwt 또는 파일 자동 생성만 허용되므로(LoadTestService 검증)
            // tokens 모드는 항상 여기서 -GenerateAccessTokens로 간다.
            if (request.generatesAccessTokensFile()) {
                command.add("-GenerateAccessTokens");
                command.add("-TokenCountPerNode");
                command.add(String.valueOf(request.generatedAccessTokenCount()));
            }
        }
    }

    Path scriptPath(final LoadTestRequest request) {
        return request.ticketProjectPath().resolve(scriptName(request.simulationType())).toAbsolutePath().normalize();
    }

    private String scriptName(final SimulationType simulationType) {
        return switch (simulationType) {
            case TICKET_OPEN_END_TO_END, SMOKE, HOT_SEAT_CONCURRENCY, CORE_ADMISSION_CAPACITY,
                    CORE_ACTIVE_USERS_CLOSED, CORE_SPIKE, QUEUE_PROTECTS_CORE -> "run-distributed-booking.ps1";
            case QUEUE_JOIN_ONLY, CDN_PUBLIC_STATE -> "run-distributed-gatling-cdn.ps1";
            default -> throw new IllegalArgumentException(
                    "Distributed execution supports only booking, 대기열 진입 요청 and CDN 공개 대기열 상태 조회"
            );
        };
    }
}
