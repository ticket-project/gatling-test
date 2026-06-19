package com.ticket.gatling.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LoadTestService {
    private final GatlingCommandBuilder commandBuilder = new GatlingCommandBuilder();
    private final ReportRegistry reportRegistry;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<UUID, LoadTestRun> runs = new LinkedHashMap<>();
    private final AtomicReference<UUID> runningRunId = new AtomicReference<>();

    public LoadTestService(final ReportRegistry reportRegistry) {
        this.reportRegistry = reportRegistry;
    }

    public synchronized LoadTestRun start(final LoadTestRequest request) {
        if (runningRunId.get() != null) {
            throw new IllegalStateException("A load test is already running");
        }
        validateConfiguredTokens(request);
        validateConfiguredAdmissionTokens(request);
        validateSyntheticJwt(request);
        validateSyntheticAdmissionToken(request);
        validateAutomaticLoginCapacity(request);
        final UUID runId = UUID.randomUUID();
        final LoadTestRun run = new LoadTestRun(runId, request);
        runs.put(runId, run);
        runningRunId.set(runId);
        executor.submit(() -> execute(run, request));
        return run;
    }

    private void validateConfiguredTokens(final LoadTestRequest request) {
        if (!request.simulationType().usesAccessTokens()) {
            return;
        }
        if ("tokens".equalsIgnoreCase(request.accessTokenMode()) && request.accessTokens().isBlank()) {
            throw new IllegalArgumentException("Access Token list is required in token mode");
        }
    }

    private void validateConfiguredAdmissionTokens(final LoadTestRequest request) {
        if (!request.simulationType().usesAdmissionTokens()) {
            return;
        }
        if ("tokens".equalsIgnoreCase(request.admissionTokenMode()) && request.admissionTokens().isBlank()) {
            throw new IllegalArgumentException("Admission Token list is required in token mode");
        }
    }

    private void validateSyntheticJwt(final LoadTestRequest request) {
        if (!request.simulationType().usesAccessTokens()) {
            return;
        }
        if (!"synthetic-jwt".equalsIgnoreCase(request.accessTokenMode())) {
            return;
        }
        if (request.jwtSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT Secret must be at least 32 bytes for synthetic JWT mode");
        }
    }

    private void validateSyntheticAdmissionToken(final LoadTestRequest request) {
        if (!request.simulationType().usesAdmissionTokens()) {
            return;
        }
        if (!"synthetic".equalsIgnoreCase(request.admissionTokenMode())) {
            return;
        }
        if (request.admissionTokenSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Admission Token Secret must be at least 32 bytes for synthetic mode");
        }
    }

    private void validateAutomaticLoginCapacity(final LoadTestRequest request) {
        if (!request.simulationType().usesAccessTokens()) {
            return;
        }
        if (!"login".equalsIgnoreCase(request.accessTokenMode())) {
            return;
        }
        if (!request.accessTokens().isBlank()) {
            return;
        }
        final int lastMemberNo = request.loginStartIndex() + request.estimatedVirtualUsers() - 1;
        if (lastMemberNo <= request.seedMemberCount()) {
            return;
        }
        final int availableUsers = Math.max(0, request.seedMemberCount() - request.loginStartIndex() + 1);
        throw new IllegalArgumentException(String.join(System.lineSeparator(),
                "자동 로그인에 필요한 테스트 회원이 부족합니다.",
                "요청 범위: " + request.loginEmailPrefix() + request.loginStartIndex()
                        + " ~ " + request.loginEmailPrefix() + lastMemberNo,
                "Seed member count: " + request.seedMemberCount(),
                "해결 방법: 사용자 수를 " + availableUsers + " 이하로 낮추거나, "
                        + "app.seed.load-test-members.count를 " + lastMemberNo + " 이상으로 늘린 뒤 ticket 서버를 재시작하세요."
        ));
    }

    public synchronized List<LoadTestRun> runs() {
        return new ArrayList<>(runs.values()).reversed();
    }

    public synchronized Optional<LoadTestRun> find(final UUID runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    private void execute(final LoadTestRun run, final LoadTestRequest request) {
        final Path executionReportsRoot = executionReportsRoot(request, run.id());
        final Set<Path> beforeReports = listReportDirectories(executionReportsRoot);
        int exitCode = -1;
        try {
            validateTicketProject(request.ticketProjectPath());
            Files.createDirectories(executionReportsRoot);
            final List<String> command = commandBuilder.build(request, executionReportsRoot);
            run.appendLog("$ " + String.join(" ", redactSensitiveArguments(command)));

            final Process process = new ProcessBuilder(command)
                    .directory(request.ticketProjectPath().toFile())
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    run.appendLog(line);
                }
            }
            exitCode = process.waitFor();
        } catch (Exception exception) {
            run.appendLog("ERROR: " + exception.getMessage());
        } finally {
            Path reportDirectory = detectReportDirectory(executionReportsRoot, beforeReports).orElse(null);
            if (reportDirectory != null) {
                reportDirectory = renameReportDirectory(reportDirectory, request, run);
                reportRegistry.register(run.id(), reportDirectory);
                run.appendLog("Report: " + reportDirectory);
            }
            deleteIfEmpty(executionReportsRoot);
            run.complete(exitCode, reportDirectory);
            runningRunId.compareAndSet(run.id(), null);
        }
    }

    private void validateTicketProject(final Path ticketProjectPath) {
        if (!Files.exists(ticketProjectPath.resolve("gradlew.bat"))) {
            throw new IllegalArgumentException("gradlew.bat not found: " + ticketProjectPath);
        }
        if (!Files.isDirectory(ticketProjectPath.resolve("load-tests").resolve("gatling"))) {
            throw new IllegalArgumentException("Gatling project not found under: " + ticketProjectPath);
        }
    }

    private Path executionReportsRoot(final LoadTestRequest request, final UUID runId) {
        return request.ticketProjectPath().resolve("load-tests").resolve("gatling")
                .resolve("build").resolve("tmp").resolve("gatling-console-runs")
                .resolve(runId.toString())
                .toAbsolutePath().normalize();
    }

    private Set<Path> listReportDirectories(final Path reportsRoot) {
        if (!Files.isDirectory(reportsRoot)) {
            return Set.of();
        }
        try (Stream<Path> stream = Files.list(reportsRoot)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.toAbsolutePath().normalize())
                    .collect(Collectors.toSet());
        } catch (IOException exception) {
            return ConcurrentHashMap.newKeySet();
        }
    }

    private Optional<Path> detectReportDirectory(final Path reportsRoot, final Set<Path> beforeReports) {
        if (!Files.isDirectory(reportsRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(reportsRoot)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> !beforeReports.contains(path))
                    .max(Comparator.comparingLong(this::lastModified));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private Path renameReportDirectory(
            final Path reportDirectory,
            final LoadTestRequest request,
            final LoadTestRun run
    ) {
        final Path target = uniqueReportDirectory(request.reportsRoot()
                .resolve(ReportDirectoryNameFormatter.format(request)));
        if (reportDirectory.equals(target)) {
            return reportDirectory;
        }
        try {
            Files.createDirectories(target.getParent());
            return Files.move(reportDirectory, target);
        } catch (IOException exception) {
            run.appendLog("Report rename skipped: " + exception.getMessage());
            return reportDirectory;
        }
    }

    private Path uniqueReportDirectory(final Path desiredDirectory) {
        if (!Files.exists(desiredDirectory)) {
            return desiredDirectory;
        }
        final Path parent = desiredDirectory.getParent();
        final String baseName = desiredDirectory.getFileName().toString();
        int suffix = 2;
        Path candidate;
        do {
            candidate = parent.resolve(baseName + " - " + suffix);
            suffix++;
        } while (Files.exists(candidate));
        return candidate;
    }

    private void deleteIfEmpty(final Path directory) {
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // Non-empty or locked directories are safe to leave under build/tmp.
        }
    }

    private long lastModified(final Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private List<String> redactSensitiveArguments(final List<String> command) {
        return command.stream()
                .map(argument -> {
                    if (argument.startsWith("-DloginPassword=")) {
                        return "-DloginPassword=****";
                    }
                    if (argument.startsWith("-DjwtSecret=")) {
                        return "-DjwtSecret=****";
                    }
                    if (argument.startsWith("-DaccessTokens=")) {
                        return "-DaccessTokens=****";
                    }
                    if (argument.startsWith("-DadmissionTokenSecret=")) {
                        return "-DadmissionTokenSecret=****";
                    }
                    if (argument.startsWith("-DadmissionTokens=")) {
                        return "-DadmissionTokens=****";
                    }
                    return argument;
                })
                .toList();
    }
}
