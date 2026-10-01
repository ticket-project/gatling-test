package com.ticket.gatling.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public class LoadTestService {
    private static final long CORE_CAPACITY_SEAT_START_ID = 910000001L;
    private static final int CORE_CAPACITY_DATA_ROWS = 2_000;
    private static final String RUN_DIR_MARKER = "Run dir:";
    static final String CONSOLE_RUN_FILE = "console-run.json";
    private static final List<String> SECRET_ARGUMENT_PREFIXES = List.of("-DjwtSecret=", "-DaccessTokens=");

    private final GatlingCommandBuilder commandBuilder = new GatlingCommandBuilder();
    private final DistributedGatlingCommandBuilder distributedCommandBuilder = new DistributedGatlingCommandBuilder();
    private final DistributedRunStopper distributedRunStopper = new DistributedRunStopper();
    private final RunEnvironmentClient environmentClient = new DatadogEnvironmentClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<UUID, LoadTestRun> runs = new LinkedHashMap<>();
    private final AtomicReference<UUID> runningRunId = new AtomicReference<>();

    public synchronized LoadTestRun start(final LoadTestRequest request) {
        if (runningRunId.get() != null) {
            throw new IllegalStateException("A load test is already running");
        }
        validate(request);
        final UUID runId = UUID.randomUUID();
        final LoadTestRun run = new LoadTestRun(runId, request);
        runs.put(runId, run);
        runningRunId.set(runId);
        executor.submit(() -> execute(run, request));
        return run;
    }

    void validate(final LoadTestRequest request) {
        validateInjectionMode(request);
        validateTargetSelection(request);
        validateConfiguredTokens(request);
        validateProofSuiteInjection(request);
        validateSyntheticJwt(request);
        validateBookingExecution(request);
        validateDistributedExecution(request);
    }

    private void validateInjectionMode(final LoadTestRequest request) {
        if ("ticket-open".equalsIgnoreCase(request.injectionMode())
                && request.simulationType() != SimulationType.QUEUE_JOIN_ONLY) {
            throw new IllegalArgumentException("예매 오픈 패턴은 대기열 진입 요청 테스트에서만 사용할 수 있습니다.");
        }
    }

    private void validateProofSuiteInjection(final LoadTestRequest request) {
        final String mode = request.injectionMode().toLowerCase(Locale.ROOT);
        if (request.simulationType() == SimulationType.CORE_ADMISSION_CAPACITY
                && !"constant-users-per-sec".equals(mode)) {
            throw new IllegalArgumentException(
                    "Core Admission Capacity requires the constant-users-per-sec injection mode"
            );
        }
        if (request.simulationType() == SimulationType.CORE_ADMISSION_CAPACITY
                && (!Double.isFinite(request.usersPerSecond()) || request.usersPerSecond() <= 0.0)) {
            throw new IllegalArgumentException("Core Admission Capacity users/sec must be positive");
        }
        if (request.simulationType() == SimulationType.CORE_ACTIVE_USERS_CLOSED
                && !"closed-core".equals(mode)) {
            throw new IllegalArgumentException("Core Active Users requires the closed-core injection mode");
        }
        if (request.simulationType() != SimulationType.CORE_ACTIVE_USERS_CLOSED
                && "closed-core".equals(mode)) {
            throw new IllegalArgumentException("closed-core injection is only available for Core Active Users");
        }
        final boolean spikeScenario = request.simulationType() == SimulationType.CORE_SPIKE
                || request.simulationType() == SimulationType.QUEUE_PROTECTS_CORE;
        if (request.simulationType() == SimulationType.CORE_SPIKE && !"spike".equals(mode)) {
            throw new IllegalArgumentException("Core 순간 부하 및 회복 requires the spike injection mode");
        }
        if ("spike".equals(mode) && !spikeScenario) {
            throw new IllegalArgumentException("spike injection is only available for Core 순간 부하 및 회복 or Queue의 Core 보호");
        }
        if ("spike".equals(mode) && request.targetUsersPerSecond() <= request.usersPerSecond()) {
            throw new IllegalArgumentException("Spike target RPS must be greater than baseline RPS");
        }
    }

    private void validateConfiguredTokens(final LoadTestRequest request) {
        if (!request.simulationType().usesAccessTokens()
                || request.simulationType().usesFeederAccessTokens()
                || !"tokens".equals(request.accessTokenMode())
                || request.generatesAccessTokensFile()) {
            return;
        }
        if (request.usesAccessTokensFile()
                && !Files.isRegularFile(resolveInputPath(request.ticketProjectPath(), request.accessTokensFile()))) {
            throw new IllegalArgumentException("Access Token file not found: " + request.accessTokensFile());
        }
        if (request.usesInlineAccessTokens() && request.accessTokens().isBlank()) {
            throw new IllegalArgumentException("Access Token list is required in token mode");
        }
    }

    private void validateSyntheticJwt(final LoadTestRequest request) {
        if (!request.simulationType().usesAccessTokens()) {
            return;
        }
        if (!"synthetic-jwt".equals(request.accessTokenMode()) && !request.generatesAccessTokensFile()) {
            return;
        }
        if (request.jwtSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT Secret must be at least 32 bytes for synthetic JWT mode");
        }
        if (requiresExistingMemberIds(request)) {
            // Booking feeder 시나리오는 feeder의 accessToken 열을 쓰므로, 파일을 새로 만들 때만 회원 ID 파일이 필요하다.
            if (!request.generatesAccessTokensFile() && request.simulationType().usesBookingFeeder()) {
                return;
            }
            if (!request.generatesAccessTokensFile()) {
                throw new IllegalArgumentException(
                        "This Core scenario requires generated JWTs from an existing Member ID file"
                );
            }
            validateMemberIdsFile(request);
        }
    }

    /**
     * 로컬 대상은 시드가 회원 ID를 1부터 연속으로 만들므로 실제 회원 ID 파일이 필요 없다.
     * 파일을 넘기지 않으면 {@code AccessTokenFileGenerator}가 syntheticMemberStartId부터 연속 ID를 쓴다.
     */
    private boolean requiresExistingMemberIds(final LoadTestRequest request) {
        return !LoadTestRequest.isLocalUrl(request.coreBaseUrl())
                && request.simulationType().usesExistingMemberIds();
    }

    private void validateMemberIdsFile(final LoadTestRequest request) {
        if (request.memberIdsFile().isBlank()) {
            throw new IllegalArgumentException("An existing Member ID file is required");
        }
        final Path memberIdsFile = resolveInputPath(request.ticketProjectPath(), request.memberIdsFile());
        if (!Files.isRegularFile(memberIdsFile)) {
            throw new IllegalArgumentException("Member ID file not found: " + request.memberIdsFile());
        }
    }

    private void validateDistributedExecution(final LoadTestRequest request) {
        if (!request.distributedExecution()) {
            return;
        }
        final SimulationType simulationType = request.simulationType();
        if (isCoreApiIsolationSimulation(simulationType)) {
            throw new IllegalArgumentException("API별 독립 성능 테스트는 현재 로컬 실행만 지원합니다");
        }
        if (simulationType == SimulationType.CORE_REALISTIC_CONTENTION) {
            throw new IllegalArgumentException("03-2 현실형 인기 좌석 경합은 피더 없이 동작하므로 현재 로컬 실행만 지원합니다");
        }
        if (simulationType == SimulationType.CORE_REALISTIC_USER_MIX) {
            throw new IllegalArgumentException("03-3 실제 사용자 흐름 혼합은 피더 없이 동작하므로 현재 로컬 실행만 지원합니다");
        }
        if (!simulationType.usesBookingFeeder()
                && simulationType != SimulationType.CDN_PUBLIC_STATE
                && simulationType != SimulationType.QUEUE_JOIN_ONLY) {
            throw new IllegalArgumentException(
                    "Distributed execution supports only booking, 대기열 진입 요청 and CDN 공개 대기열 상태 조회"
            );
        }
        if (!request.closedBookingModel() && !(request.usersPerSecond() > 0)) {
            throw new IllegalArgumentException(
                    "EC2 분산 실행에서는 시작 초당 사용자 수를 노드당 RPS로 사용하므로 0보다 커야 합니다"
            );
        }
        if (simulationType != SimulationType.QUEUE_JOIN_ONLY) {
            return;
        }
        if ("synthetic-jwt".equals(request.accessTokenMode()) || request.generatesAccessTokensFile()) {
            return;
        }
        throw new IllegalArgumentException(
                "대기열 진입 요청 distributed execution requires synthetic JWT or generated access token file mode"
        );
    }

    private void validateBookingExecution(final LoadTestRequest request) {
        if (!request.simulationType().usesCoreBookingFlow()) {
            return;
        }
        // 분산 실행은 VM에서 요청하므로 localhost가 대상 서버를 가리키지 않는다.
        // 로컬 Console 실행은 로컬 Ticket/Core를 대상으로 삼을 수 있어야 하므로 URL 형식만 본다.
        validateUrl("Core URL", request.coreBaseUrl(), request.distributedExecution());
        if (request.simulationType().usesQueueBaseUrl()) {
            validateUrl("Queue URL", request.queueBaseUrl(), request.distributedExecution());
        }
        if (request.distributedExecution()
                && request.simulationType() == SimulationType.HOT_SEAT_CONCURRENCY) {
            throw new IllegalArgumentException("Hot Seat must run on one load generator because rendezVous is process-local");
        }
        if (request.closedBookingModel() && request.bookingFeederRows() < request.users()) {
            throw new IllegalArgumentException("Closed model feeder rows must be at least concurrent users");
        }
        if (request.simulationType() == SimulationType.QUEUE_PROTECTS_CORE
                && request.maxCoreAdmissionsPerSecond() <= 0) {
            throw new IllegalArgumentException("Queue의 Core 보호 requires a positive Core admission limit");
        }
        if (!LoadTestRequest.isLocalUrl(request.coreBaseUrl()) && !request.operationalConfirmation()) {
            throw new IllegalArgumentException(
                    "Operational confirmation is required for a non-local booking execution"
            );
        }
        if (request.simulationType().usesBookingFeeder()) {
            if (generatesMemberSeatFeeder(request)) {
                validateGeneratedMemberSeatFeeder(request);
                return;
            }
            final Path feederPath = resolveInputPath(request.ticketProjectPath(), request.bookingFeederFile());
            if (!Files.isRegularFile(feederPath)) {
                throw new IllegalArgumentException("Booking feeder file not found: " + request.bookingFeederFile());
            }
            final int nodeCount = request.distributedExecution() ? request.distributedHostList().size() : 1;
            final int requiredRows = Math.multiplyExact(request.expectedBookingRowsPerNode(), nodeCount);
            final long requiredRowsWithOffset = (long) request.bookingFeederOffset() + requiredRows;
            final int actualRows = countBookingFeederRows(feederPath, request.simulationType());
            if (actualRows < requiredRowsWithOffset) {
                throw new IllegalArgumentException("Booking feeder has fewer rows than required for offset: offset="
                        + request.bookingFeederOffset() + ", expected=" + requiredRows + ", required="
                        + requiredRowsWithOffset + ", actual=" + actualRows);
            }
        }
    }

    private boolean generatesMemberSeatFeeder(final LoadTestRequest request) {
        return request.generatesAccessTokensFile()
                && (request.simulationType() == SimulationType.CORE_ADMISSION_CAPACITY
                || request.simulationType() == SimulationType.CORE_SEAT_SELECT_API
                || request.simulationType() == SimulationType.CORE_ORDER_CREATE_API);
    }

    private void validateGeneratedMemberSeatFeeder(final LoadTestRequest request) {
        if (request.distributedExecution()) {
            throw new IllegalArgumentException(
                    "Automatic JWT/member-seat feeder generation currently supports local Console execution only"
            );
        }
        if (requiresExistingMemberIds(request)) {
            validateMemberIdsFile(request);
        }
        final long requiredRows = (long) request.bookingFeederOffset() + request.expectedBookingRowsPerNode();
        if (request.generatedAccessTokenCount() < requiredRows) {
            throw new IllegalArgumentException(
                    "Automatic member-seat feeder rows are insufficient: generated="
                            + request.generatedAccessTokenCount() + ", required=" + requiredRows
            );
        }
        if (request.generatedAccessTokenCount() > CORE_CAPACITY_DATA_ROWS) {
            throw new IllegalArgumentException(
                    "Automatic member-seat feeder exceeds the dedicated 2,000 seats: generated="
                            + request.generatedAccessTokenCount() + ", available=" + CORE_CAPACITY_DATA_ROWS
            );
        }
    }

    private void validateTargetSelection(final LoadTestRequest request) {
        if (request.simulationType().usesCoreBookingFlow()) {
            return;
        }
        validateUrl("Base URL", request.baseUrl(), false);
        if (!LoadTestRequest.isLocalUrl(request.baseUrl()) && !request.operationalConfirmation()) {
            throw new IllegalArgumentException(
                    "Operational confirmation is required for a non-local load-test target"
            );
        }
    }

    private void validateUrl(final String name, final String value, final boolean remoteOnly) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            final URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException(name + " must be an absolute URL: " + value);
            }
            if (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https")) {
                throw new IllegalArgumentException(name + " must use http or https: " + value);
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(name + " must be an absolute URL: " + value, exception);
        }
        if (remoteOnly && LoadTestRequest.isLocalUrl(value)) {
            throw new IllegalArgumentException(name + " must not point to localhost: " + value);
        }
    }

    private int countBookingFeederRows(final Path feederPath, final SimulationType simulationType) {
        try {
            final byte[] bytes = Files.readAllBytes(feederPath);
            if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
                throw new IllegalArgumentException("Booking feeder must be UTF-8 without BOM: " + feederPath);
            }
            final List<String> lines = Files.readAllLines(feederPath, StandardCharsets.UTF_8);
            final String header = lines.isEmpty() ? "" : lines.getFirst();
            final boolean dynamicSeatSelection = simulationType == SimulationType.CORE_ACTIVE_USERS_CLOSED
                    || simulationType == SimulationType.CORE_SPIKE;
            final boolean orderLookup = simulationType == SimulationType.CORE_ORDER_GET_API;
            final boolean validHeader = orderLookup
                    ? "memberId,orderKey".equals(header)
                    : "memberId,accessToken,seatId,admissionToken".equals(header)
                    || (dynamicSeatSelection && "memberId,accessToken,admissionToken".equals(header));
            if (!validHeader) {
                throw new IllegalArgumentException("Booking feeder header is invalid");
            }
            return (int) lines.stream().skip(1).filter(line -> !line.isBlank()).count();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Booking feeder file cannot be read: " + feederPath, exception);
        }
    }

    private static boolean isCoreApiIsolationSimulation(final SimulationType simulationType) {
        return switch (simulationType) {
            case CORE_PERFORMANCE_SUMMARY_API, CORE_SEAT_STATUS_API, CORE_SEAT_SELECT_API,
                    CORE_ORDER_CREATE_API, CORE_ORDER_GET_API -> true;
            default -> false;
        };
    }

    public synchronized List<LoadTestRun> runs() {
        return new ArrayList<>(runs.values()).reversed();
    }

    public synchronized Optional<LoadTestRun> find(final UUID runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    public LoadTestRun stop(final UUID runId) {
        final LoadTestRun run = find(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (!run.requestStop()) {
            throw new IllegalStateException("Run is not running: " + runId);
        }
        if (run.request().distributedExecution()) {
            distributedRunStopper.stop(run.request(), runId, run::appendLog);
        }
        return run;
    }

    private void execute(final LoadTestRun run, final LoadTestRequest request) {
        // 로컬 실행 폴더는 runId마다 새로 만들므로 그 안의 디렉터리는 모두 이번 실행 결과다.
        final Path executionReportsRoot = executionReportsRoot(request, run.id());
        int exitCode = -1;
        try {
            validateRunnableProject(request);
            final RunEnvironmentMetadata metadata = RunEnvironmentMetadata.capture(request, environmentClient);
            run.environmentMetadata(metadata);
            run.appendLog("Environment metadata: " + metadata.captureStatus());
            if (metadata.captureError() != null) {
                run.appendLog("Environment metadata note: " + metadata.captureError());
            }
            if (!metadata.captureWarnings().isEmpty()) {
                run.appendLog("Environment metadata warnings: "
                        + String.join("; ", metadata.captureWarnings()));
            }
            if (run.stopRequested()) {
                return;
            }
            prepareGeneratedAccessTokens(run, request);
            if (run.stopRequested()) {
                return;
            }
            if (!request.distributedExecution()) {
                Files.createDirectories(executionReportsRoot);
            }
            exitCode = runLogged(run, buildCommand(run, request, executionReportsRoot));
        } catch (Exception exception) {
            run.appendLog("ERROR: " + exception.getMessage());
        } finally {
            boolean createdFailureReport = false;
            Path reportDirectory;
            if (request.distributedExecution()) {
                reportDirectory = parseDistributedRunDirectory(run.log())
                        .filter(Files::isDirectory)
                        .or(() -> latestDirectory(request.reportsRoot()))
                        .orElse(null);
            } else {
                final Path resultDirectory = latestDirectory(executionReportsRoot).orElse(null);
                if (resultDirectory != null
                        && !hasHtmlReport(resultDirectory)
                        && Files.isRegularFile(resultDirectory.resolve("simulation.log"))
                        && exitCode != 0
                        && !run.stopRequested()) {
                    recoverHtmlReport(run, request, executionReportsRoot, resultDirectory);
                }
                reportDirectory = resultDirectory != null && hasHtmlReport(resultDirectory)
                        ? resultDirectory
                        : null;
            }
            if (reportDirectory == null && exitCode != 0 && !run.stopRequested()) {
                reportDirectory = createFailureReportDirectory(request, run, exitCode);
                createdFailureReport = reportDirectory != null;
            }
            if (reportDirectory != null) {
                writeConsoleRun(reportDirectory, run, exitCode);
                if (request.distributedExecution()) {
                    writeRunMetadata(reportDirectory, run);
                    writeDistributedIndex(reportDirectory, run);
                } else {
                    if (!createdFailureReport) {
                        reportDirectory = renameReportDirectory(reportDirectory, request, run);
                    }
                    writeRunMetadata(reportDirectory, run);
                    if (request.simulationType().usesCoreBookingFlow()) {
                        copyLocalBookingArtifacts(request, reportDirectory, run);
                    }
                }
                run.appendLog("Report: " + reportDirectory);
            }
            if (!request.distributedExecution()) {
                deleteIfEmpty(executionReportsRoot);
            }
            run.complete(exitCode, reportDirectory == null ? null : reportDirectory.toAbsolutePath().normalize());
            runningRunId.compareAndSet(run.id(), null);
        }
    }

    /** 프로세스를 실행하고 출력을 실행 로그로 흘린 뒤 종료 코드를 돌려준다. 중지 요청 시 프로세스 트리를 끊는다. */
    private int runLogged(final LoadTestRun run, final List<String> command) throws IOException, InterruptedException {
        run.appendLog("$ " + String.join(" ", redactSensitiveArguments(command)));
        final Process process = new ProcessBuilder(command)
                .directory(run.request().ticketProjectPath().toFile())
                .redirectErrorStream(true)
                .start();
        run.attachProcess(process);
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                run.appendLog(line);
            }
            return process.waitFor();
        } finally {
            run.clearProcess(process);
        }
    }

    private void recoverHtmlReport(
            final LoadTestRun run,
            final LoadTestRequest request,
            final Path reportsRoot,
            final Path resultDirectory
    ) {
        run.appendLog("Gatling HTML report was not generated. Rebuilding it from simulation.log.");
        try {
            final int reportExitCode = runLogged(run, commandBuilder.buildReport(
                    request,
                    reportsRoot,
                    resultDirectory.getFileName().toString()
            ));
            if (reportExitCode != 0) {
                run.appendLog("Gatling HTML report rebuild exited with code " + reportExitCode);
            }
        } catch (IOException exception) {
            run.appendLog("Gatling HTML report rebuild failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            run.appendLog("Gatling HTML report rebuild was interrupted");
        }
    }

    private void copyLocalBookingArtifacts(
            final LoadTestRequest request,
            final Path reportDirectory,
            final LoadTestRun run
    ) {
        final Path resultFile = resolveInputPath(
                request.ticketProjectPath().resolve("load-tests").resolve("gatling"),
                request.resultFile()
        );
        final Path evidenceDirectory = resultFile.getParent();
        if (evidenceDirectory == null) {
            return;
        }
        final Map<Path, String> artifacts = new LinkedHashMap<>();
        artifacts.put(resultFile, "booking-results.csv");
        artifacts.put(evidenceDirectory.resolve("booking-evidence.json"), "booking-evidence.json");
        artifacts.put(evidenceDirectory.resolve("booking-admissions.csv"), "booking-admissions.csv");
        artifacts.put(evidenceDirectory.resolve("booking-completions.csv"), "booking-completions.csv");
        artifacts.put(evidenceDirectory.resolve("booking-active-users.csv"), "booking-active-users.csv");
        artifacts.put(evidenceDirectory.resolve("booking-run-config.json"), "booking-run-config.json");
        artifacts.put(evidenceDirectory.resolve("booking-db-audit.json"), "booking-db-audit.json");
        artifacts.forEach((source, fileName) -> {
            if (!Files.isRegularFile(source)) {
                return;
            }
            try {
                // 증거 파일은 실행마다 같은 폴더에 덮어쓴다. 이번 실행이 쓰지 않은 파일은 지난 실행의 것이다.
                if (Files.getLastModifiedTime(source).toInstant().isBefore(run.startedAt())) {
                    run.appendLog("Booking artifact skipped (written by an earlier run): " + source);
                    return;
                }
                Files.copy(source, reportDirectory.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                run.appendLog("Booking artifact copy skipped for " + source + ": " + exception.getMessage());
            }
        });
    }

    /** 수용량 패널이 폴더만 보고 판정할 수 있도록 종료 코드와 중지 여부를 남긴다. */
    private void writeConsoleRun(final Path reportDirectory, final LoadTestRun run, final int exitCode) {
        try {
            Files.writeString(
                    reportDirectory.resolve(CONSOLE_RUN_FILE),
                    "{\"runId\":\"" + run.id() + "\",\"simulation\":\"" + run.request().simulationType().key()
                            + "\",\"exitCode\":" + exitCode + ",\"stopped\":" + run.stopRequested() + "}",
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            run.appendLog("Console run result write skipped: " + exception.getMessage());
        }
    }

    private void writeRunMetadata(final Path reportDirectory, final LoadTestRun run) {
        final RunEnvironmentMetadata metadata = run.environmentMetadata();
        if (metadata == null) {
            return;
        }
        try {
            Files.writeString(
                    reportDirectory.resolve("run-metadata.json"),
                    metadata.toJson(run.id()),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            run.appendLog("Environment metadata write skipped: " + exception.getMessage());
        }
    }

    private Path createFailureReportDirectory(
            final LoadTestRequest request,
            final LoadTestRun run,
            final int exitCode
    ) {
        final Path reportDirectory = uniqueReportDirectory(request.reportsRoot()
                .resolve(ReportDirectoryNameFormatter.format(request) + " - failed"));
        try {
            Files.createDirectories(reportDirectory);
            Files.writeString(reportDirectory.resolve("run.log"), run.log(), StandardCharsets.UTF_8);
            Files.writeString(
                    reportDirectory.resolve("index.html"),
                    failureReportHtml(request, run, exitCode, reportDirectory),
                    StandardCharsets.UTF_8
            );
            return reportDirectory;
        } catch (IOException exception) {
            run.appendLog("Failure report creation skipped: " + exception.getMessage());
            return null;
        }
    }

    private String failureReportHtml(
            final LoadTestRequest request,
            final LoadTestRun run,
            final int exitCode,
            final Path reportDirectory
    ) {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Gatling Run Failed</title>"
                + "<style>"
                + "body{font-family:Segoe UI,system-ui,sans-serif;margin:0;color:#17202c;background:#eef2f7;}"
                + "main{padding:22px 24px 28px;}h1{font-size:22px;margin:0 0 8px;}"
                + ".muted{color:#66758a;font-size:12px}.path{overflow-wrap:anywhere}"
                + "pre{white-space:pre-wrap;word-break:break-word;background:#111827;color:#d8dee9;"
                + "border-radius:8px;padding:14px;line-height:1.55;font-size:12px;}"
                + "a{color:#1d4ed8;font-weight:650;text-decoration:none;}"
                + "</style></head><body><main>"
                + "<h1>Gatling run failed before report generation</h1>"
                + "<div class=\"muted path\">Run directory: " + htmlEscape(reportDirectory.toString()) + "</div>"
                + "<p>Simulation: " + htmlEscape(request.simulationType().label())
                + " / exitCode: " + exitCode + "</p>"
                + "<p><a href=\"run.log\">run.log</a> · <a href=\"run-metadata.json\">run-metadata.json</a></p>"
                + "<h2>Console log</h2><pre>" + htmlEscape(run.log()) + "</pre>"
                + "</main></body></html>";
    }

    private List<String> buildCommand(
            final LoadTestRun run,
            final LoadTestRequest request,
            final Path executionReportsRoot
    ) {
        final RunEnvironmentMetadata metadata = run.environmentMetadata();
        final String description = metadata == null ? "runId=" + run.id() : metadata.runDescription(run.id());
        if (request.distributedExecution()) {
            return distributedCommandBuilder.build(request, run.id(), description);
        }
        final List<String> command = new ArrayList<>(
                commandBuilder.build(request, executionReportsRoot, description));
        command.add("-DconsoleRunId=" + run.id());
        return List.copyOf(command);
    }

    private void prepareGeneratedAccessTokens(
            final LoadTestRun run,
            final LoadTestRequest request
    ) throws IOException, InterruptedException {
        if (!request.generatesAccessTokensFile() || request.distributedExecution()) {
            return;
        }
        run.appendLog("Generating access token file before Gatling run.");
        final int exitCode = runLogged(run, accessTokenGenerationCommand(request));
        if (!run.stopRequested() && exitCode != 0) {
            throw new IllegalStateException("Access token generation failed with exit code " + exitCode);
        }
    }

    private List<String> accessTokenGenerationCommand(final LoadTestRequest request) {
        final List<String> command = new ArrayList<>();
        command.add(request.gradleWrapper());
        command.add("-p");
        command.add("load-tests/gatling");
        command.add("generateAccessTokens");
        command.add("-Doutput=" + request.accessTokensFile());
        command.add("-DtokenCount=" + request.generatedAccessTokenCount());
        command.add("-DjwtSecret=" + request.jwtSecret());
        command.add("-DjwtIssuer=" + request.jwtIssuer());
        command.add("-DsyntheticMemberStartId=" + request.syntheticMemberStartId());
        if (requiresExistingMemberIds(request)) {
            command.add("-DmemberIdsFile=" + resolveInputPath(
                    request.ticketProjectPath(), request.memberIdsFile()));
        }
        command.add("-DsyntheticJwtRole=" + request.syntheticJwtRole());
        command.add("-DsyntheticTokenTtlSeconds=" + request.syntheticTokenTtlSeconds());
        if (generatesMemberSeatFeeder(request)) {
            command.add("-DbookingFeederOutput=" + request.bookingFeederFile());
            command.add("-DbookingSeatStartId=" + CORE_CAPACITY_SEAT_START_ID);
        }
        return List.copyOf(command);
    }

    private Path resolveInputPath(final Path baseDirectory, final String value) {
        final Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return baseDirectory.resolve(path).toAbsolutePath().normalize();
    }

    private void validateRunnableProject(final LoadTestRequest request) {
        final Path ticketProjectPath = request.ticketProjectPath();
        if (!Files.exists(ticketProjectPath.resolve("gradlew.bat"))) {
            throw new IllegalArgumentException("gradlew.bat not found: " + ticketProjectPath);
        }
        if (!Files.isDirectory(ticketProjectPath.resolve("load-tests").resolve("gatling"))) {
            throw new IllegalArgumentException("Gatling load-tests project not found under: " + ticketProjectPath);
        }
        if (!request.distributedExecution()) {
            return;
        }
        final Path scriptPath = distributedCommandBuilder.scriptPath(request);
        if (!Files.isRegularFile(scriptPath)) {
            throw new IllegalArgumentException("Distributed script not found: " + scriptPath);
        }
    }

    private Path executionReportsRoot(final LoadTestRequest request, final UUID runId) {
        return request.ticketProjectPath().resolve("load-tests").resolve("gatling")
                .resolve("build").resolve("tmp").resolve("gatling-console-runs")
                .resolve(runId.toString())
                .toAbsolutePath().normalize();
    }

    /** 분산 스크립트가 로그에 남긴 마지막 "Run dir: ..." 경로. */
    private Optional<Path> parseDistributedRunDirectory(final String log) {
        return log.lines()
                .filter(line -> line.contains(RUN_DIR_MARKER))
                .reduce((first, second) -> second)
                .map(line -> line.substring(line.indexOf(RUN_DIR_MARKER) + RUN_DIR_MARKER.length()).trim())
                .filter(value -> !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize());
    }

    private Optional<Path> latestDirectory(final Path root) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.toAbsolutePath().normalize())
                    .max(Comparator.comparingLong(this::lastModified));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private void writeDistributedIndex(final Path runDirectory, final LoadTestRun run) {
        final Path index = runDirectory.resolve("index.html");
        final StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Distributed Gatling Summary</title>")
                .append("<style>")
                .append("body{font-family:Segoe UI,system-ui,sans-serif;margin:0;color:#17202c;background:#eef2f7;}")
                .append("main{padding:22px 24px 28px;}h1{font-size:22px;margin:0 0 8px;}h2{font-size:16px;margin:24px 0 10px;}")
                .append("a{color:#1d4ed8;font-weight:650;text-decoration:none;}a:hover{text-decoration:underline;}")
                .append(".muted{color:#66758a;font-size:12px}.path{overflow-wrap:anywhere}.actions{display:flex;gap:10px;flex-wrap:wrap;margin:14px 0 18px;}")
                .append(".actions a{border:1px solid #b8c3d1;border-radius:6px;background:#fff;padding:7px 10px;}")
                .append("pre{padding:14px;border:1px solid #d7dee8;background:#fff;overflow:auto;}")
                .append("</style></head><body>")
                .append("<main>")
                .append("<h1>Distributed Gatling Summary</h1>")
                .append("<div class=\"muted path\">Run directory: ")
                .append(htmlEscape(runDirectory.toString()))
                .append("</div><div class=\"actions\">");
        for (String fileName : List.of("summary.csv", "summary.md", "run-metadata.json", "booking-summary.json",
                "booking-results-merged.csv", "booking-admissions-global.csv", "booking-db-audit.json")) {
            if (Files.isRegularFile(runDirectory.resolve(fileName))) {
                html.append("<a href=\"").append(htmlEscape(fileName)).append("\">")
                        .append(htmlEscape(fileName)).append("</a>");
            }
        }
        html.append("</div>");

        final Path summary = runDirectory.resolve("summary.md");
        if (Files.isRegularFile(summary)) {
            try {
                html.append("<h2>Summary</h2><pre>")
                        .append(htmlEscape(Files.readString(summary, StandardCharsets.UTF_8)))
                        .append("</pre>");
            } catch (IOException exception) {
                run.appendLog("Distributed summary preview skipped: " + exception.getMessage());
            }
        }

        // 노드별 Gatling 리포트와 -DumpFailureBody로 저장된 실패 응답 body(failure-bodies/*.html)
        html.append("<h2>Node reports</h2><ul>");
        try (Stream<Path> stream = Files.walk(runDirectory, 8)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.equals(index))
                    .filter(path -> path.getFileName().toString().equals("index.html")
                            || (path.toString().contains("failure-bodies")
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html")))
                    .sorted()
                    .forEach(path -> {
                        final String relative = runDirectory.relativize(path).toString();
                        html.append("<li><a href=\"")
                                .append(htmlEscape(relative.replace('\\', '/')))
                                .append("\">")
                                .append(htmlEscape(relative))
                                .append("</a></li>");
                    });
        } catch (IOException exception) {
            run.appendLog("Node report list skipped: " + exception.getMessage());
        }
        html.append("</ul></main></body></html>");

        try {
            Files.writeString(index, html.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            run.appendLog("Distributed index write failed: " + exception.getMessage());
        }
    }

    private String htmlEscape(final String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private boolean hasHtmlReport(final Path resultDirectory) {
        return Files.isRegularFile(resultDirectory.resolve("index.html"));
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
        final List<String> redacted = new ArrayList<>();
        boolean redactNext = false;
        for (String argument : command) {
            if (redactNext) {
                redacted.add("****");
                redactNext = false;
                continue;
            }
            redactNext = argument.equals("-JwtSecret");
            redacted.add(SECRET_ARGUMENT_PREFIXES.stream()
                    .filter(argument::startsWith)
                    .findFirst()
                    .map(prefix -> prefix + "****")
                    .orElse(argument));
        }
        return List.copyOf(redacted);
    }
}
