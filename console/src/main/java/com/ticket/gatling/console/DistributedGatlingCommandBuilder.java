package com.ticket.gatling.console;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DistributedGatlingCommandBuilder {

    public List<String> build(final LoadTestRequest request) {
        final List<String> command = new ArrayList<>();
        command.add(powershellExecutable());
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(scriptPath(request).toString());
        command.add("-KeyPath");
        command.add(request.sshKeyPath().toString());
        command.add("-Hosts");
        command.add(String.join(",", request.distributedHostList()));
        command.add("-RemoteProjectDir");
        command.add(request.distributedRemoteProjectDir());
        command.add("-RpsPerNode");
        command.add(String.valueOf((int) Math.ceil(request.usersPerSecond())));
        command.add("-DurationSeconds");
        command.add(String.valueOf(request.durationSeconds()));
        command.add("-PerformanceId");
        command.add(request.performanceId());

        if (request.simulationType().usesBookingFeeder()) {
            addBookingArguments(command, request);
        } else {
            addLegacyDistributedArguments(command, request);
        }

        if (request.distributedIncludeLocal() && !request.simulationType().usesBookingFeeder()) {
            command.add("-IncludeLocal");
        }
        if (request.distributedDumpFailureBody() && !request.simulationType().usesBookingFeeder()) {
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
        command.add("-Simulation");
        command.add(request.simulationType().className());
        command.add("-CoreBaseUrl");
        command.add(request.coreBaseUrl());
        command.add("-QueueBaseUrl");
        command.add(request.queueBaseUrl());
        command.add("-FeederFile");
        command.add(request.bookingFeederFile());
        command.add("-InjectionMode");
        command.add(request.injectionMode());
        command.add("-PollingTimeoutSeconds");
        command.add(String.valueOf(request.pollingTimeoutSeconds()));
        command.add("-StatusPollPauseSeconds");
        command.add(String.valueOf(request.statusPollPauseSeconds()));
        command.add("-StatusPollPauseJitterSeconds");
        command.add(String.valueOf(request.statusPollPauseJitterSeconds()));
    }

    private void addLegacyDistributedArguments(final List<String> command, final LoadTestRequest request) {
        command.add("-BaseUrl");
        command.add(request.baseUrl());
        command.add("-StatusPolls");
        command.add(String.valueOf(request.statusPolls()));
        command.add("-StatusPollPauseSeconds");
        command.add(String.valueOf(request.statusPollPauseSeconds()));
        command.add("-StatusPollPauseJitterSeconds");
        command.add(String.valueOf(request.statusPollPauseJitterSeconds()));

        if (request.simulationType().usesAccessTokens()) {
            command.add("-AccessTokenMode");
            command.add(request.accessTokenMode());
            if ("synthetic-jwt".equalsIgnoreCase(request.accessTokenMode()) || request.generatesAccessTokensFile()) {
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
            if (request.generatesAccessTokensFile()) {
                command.add("-GenerateAccessTokens");
                command.add("-TokenCountPerNode");
                command.add(String.valueOf(request.generatedAccessTokenCount()));
            } else if (request.usesAccessTokensFile()) {
                command.add("-AccessTokensFile");
                command.add(request.accessTokensFile());
            }
        }
    }

    Path scriptPath(final LoadTestRequest request) {
        return request.ticketProjectPath().resolve(scriptName(request.simulationType())).toAbsolutePath().normalize();
    }

    private String scriptName(final SimulationType simulationType) {
        return switch (simulationType) {
            case BOOKING_CAPACITY, TICKET_OPEN_END_TO_END, SEAT_CONTENTION -> "run-distributed-booking.ps1";
            case QUEUE_JOIN_ONLY -> "run-distributed-gatling-join.ps1";
            case CDN_PUBLIC_STATE -> "run-distributed-gatling-cdn.ps1";
            case LEGACY_QUEUE_STATUS -> "run-distributed-gatling-legacy.ps1";
            default -> throw new IllegalArgumentException(
                    "Distributed execution supports only booking, Queue Join Only, CDN Public State and Legacy Queue Status"
            );
        };
    }

    private String powershellExecutable() {
        final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        if (!windows) {
            return "pwsh";
        }

        final String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        final List<Path> candidates = List.of(
                Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe"),
                Path.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"),
                Path.of("C:\\Windows\\Sysnative\\WindowsPowerShell\\v1.0\\powershell.exe")
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return "powershell.exe";
    }
}