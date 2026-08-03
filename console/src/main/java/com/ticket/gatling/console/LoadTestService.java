package com.ticket.gatling.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LoadTestService {
    private static final int FAILURE_BODY_PREVIEW_LIMIT = 4_000;

    private final GatlingCommandBuilder commandBuilder = new GatlingCommandBuilder();
    private final DistributedGatlingCommandBuilder distributedCommandBuilder = new DistributedGatlingCommandBuilder();
    private final DistributedRunStopper distributedRunStopper = new DistributedRunStopper();
    private final RunEnvironmentClient environmentClient = new DatadogEnvironmentClient();
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
        validateInjectionMode(request);
        validateConfiguredTokens(request);
        validateConfiguredAdmissionTokens(request);
        validateProofSuiteInjection(request);
        validateSyntheticJwt(request);
        validateSyntheticAdmissionToken(request);
        validateAutomaticLoginCapacity(request);
        validateBookingExecution(request);
        validateDistributedExecution(request);
        final UUID runId = UUID.randomUUID();
        final LoadTestRun run = new LoadTestRun(runId, request);
        runs.put(runId, run);
        runningRunId.set(runId);
        executor.submit(() -> execute(run, request));
        return run;
    }

    private void validateInjectionMode(final LoadTestRequest request) {
        if ("ticket-open".equalsIgnoreCase(request.injectionMode())
                && request.simulationType() != SimulationType.QUEUE_JOIN_ONLY) {
            throw new IllegalArgumentException("예매 오픈 패턴은 Queue Join Only 테스트에서만 사용할 수 있습니다.");
        }
    }
    private void validateProofSuiteInjection(final LoadTestRequest request) {
        final String mode = request.injectionMode().toLowerCase(Locale.ROOT);
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
            throw new IllegalArgumentException("Core Spike requires the spike injection mode");
        }
        if ("spike".equals(mode) && !spikeScenario) {
            throw new IllegalArgumentException("spike injection is only available for Core Spike or Queue Protects Core");
        }
        if ("spike".equals(mode) && request.targetUsersPerSecond() <= request.usersPerSecond()) {
            throw new IllegalArgumentException("Spike target RPS must be greater than baseline RPS");
        }
    }


    private void validateConfiguredTokens(final LoadTestRequest request) {
        if (!request.simulationType().usesAccessTokens()) {
            return;
        }
        if (!"tokens".equalsIgnoreCase(request.accessTokenMode())) {
            return;
        }
        if (request.generatesAccessTokensFile()) {
            return;
        }
        if (request.usesAccessTokensFile() && request.accessTokensFile().isBlank()) {
            throw new IllegalArgumentException("Access Token file is required in token mode");
        }
        if (request.usesAccessTokensFile()
                && !request.generatesAccessTokensFile()
                && !Files.isRegularFile(resolveInputPath(request.ticketProjectPath(), request.accessTokensFile()))) {
            throw new IllegalArgumentException("Access Token file not found: " + request.accessTokensFile());
        }
        if (request.usesInlineAccessTokens() && request.accessTokens().isBlank()) {
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
        if (!"synthetic-jwt".equalsIgnoreCase(request.accessTokenMode())
                && !request.generatesAccessTokensFile()) {
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

    private void validateDistributedExecution(final LoadTestRequest request) {
        if (!request.distributedExecution()) {
            return;
        }
        if (request.simulationType() == SimulationType.CORE_ADMISSION_CAPACITY) {
            throw new IllegalArgumentException("03 Core capacity without a feeder currently supports local execution only");
        }
        if (!request.simulationType().usesBookingFeeder()
                && request.simulationType() != SimulationType.CDN_PUBLIC_STATE
                && request.simulationType() != SimulationType.LEGACY_QUEUE_STATUS
                && request.simulationType() != SimulationType.QUEUE_JOIN_ONLY) {
            throw new IllegalArgumentException(
                    "Distributed execution supports only booking, Queue Join Only, CDN Public State and Legacy Queue Status"
            );
        }
        if (request.simulationType().usesBookingFeeder()) {
            return;
        }
        if (request.simulationType() != SimulationType.QUEUE_JOIN_ONLY) {
            return;
        }
        if ("synthetic-jwt".equalsIgnoreCase(request.accessTokenMode()) || request.generatesAccessTokensFile()) {
            return;
        }
        throw new IllegalArgumentException(
                "Queue Join Only distributed execution requires synthetic JWT or generated access token file mode"
        );
    }

    private void validateBookingExecution(final LoadTestRequest request) {
        if (!request.simulationType().usesCoreBookingFlow()) {
            return;
        }
        validateRemoteUrl("Core URL", request.coreBaseUrl());
        if (request.simulationType().usesQueueBaseUrl()) {
            validateRemoteUrl("Queue URL", request.queueBaseUrl());
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
            throw new IllegalArgumentException("Queue Protects Core requires a positive Core admission limit");
        }
        if (request.dbAuditEnabled()) {
            for (String name : List.of(
                    "BOOKING_AUDIT_DB_URL",
                    "BOOKING_AUDIT_DB_USERNAME",
                    "BOOKING_AUDIT_DB_PASSWORD"
            )) {
                if (System.getenv(name) == null || System.getenv(name).isBlank()) {
                    throw new IllegalArgumentException("DB audit requires environment variable: " + name);
                }
            }
        }
        if (!request.operationalConfirmation()) {
            throw new IllegalArgumentException("Operational confirmation is required for every booking execution");
        }
        if (request.simulationType().usesBookingFeeder()) {
            final Path feederPath = resolveInputPath(request.ticketProjectPath(), request.bookingFeederFile());
            if (!Files.isRegularFile(feederPath)) {
                throw new IllegalArgumentException("Booking feeder file not found: " + request.bookingFeederFile());
            }
            final int nodeCount = request.distributedExecution() ? request.distributedHostList().size() : 1;
            final int requiredRows = Math.multiplyExact(request.expectedBookingRowsPerNode(), nodeCount);
            final int actualRows = countBookingFeederRows(feederPath, request.simulationType());
            if (actualRows < requiredRows) {
                throw new IllegalArgumentException("Booking feeder has fewer rows than required: required="
                        + requiredRows + ", actual=" + actualRows);
            }
        }
    }

    private void validateRemoteUrl(final String name, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            final URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException(name + " must be an absolute URL: " + value);
            }
            final String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")) {
                throw new IllegalArgumentException(name + " must not point to localhost: " + value);
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(name + " must be an absolute URL: " + value, exception);
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
            final boolean dynamicSeatSelection = switch (simulationType) {
                case CORE_ACTIVE_USERS_CLOSED, CORE_SPIKE -> true;
                default -> false;
            };
            final boolean validHeader = "memberId,accessToken,seatId,admissionToken".equals(header)
                    || (dynamicSeatSelection && "memberId,accessToken,admissionToken".equals(header));
            if (!validHeader) {
                throw new IllegalArgumentException("Booking feeder header is invalid");
            }
            return (int) lines.stream().skip(1).filter(line -> !line.isBlank()).count();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Booking feeder file cannot be read: " + feederPath, exception);
        }
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
        final Path executionReportsRoot = executionReportsRoot(request, run.id());
        final Set<Path> beforeReports = listReportDirectories(executionReportsRoot);
        Path distributedRunDirectory = null;
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
            final List<String> command = buildCommand(run, request, executionReportsRoot);
            run.appendLog("$ " + String.join(" ", redactSensitiveArguments(command)));

            final Process process = new ProcessBuilder(command)
                    .directory(request.ticketProjectPath().toFile())
                    .redirectErrorStream(true)
                    .start();
            run.attachProcess(process);
            try {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
                )) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        run.appendLog(line);
                        if (request.distributedExecution()) {
                            distributedRunDirectory = parseDistributedRunDirectory(line).orElse(distributedRunDirectory);
                        }
                    }
                }
                exitCode = process.waitFor();
            } finally {
                run.clearProcess(process);
            }
        } catch (Exception exception) {
            run.appendLog("ERROR: " + exception.getMessage());
        } finally {
            boolean createdFailureReport = false;
            Path reportDirectory = request.distributedExecution()
                    ? resolveDistributedReportDirectory(request, distributedRunDirectory).orElse(null)
                    : detectReportDirectory(executionReportsRoot, beforeReports).orElse(null);
            if (reportDirectory == null && exitCode != 0 && !run.stopRequested()) {
                reportDirectory = createFailureReportDirectory(request, run, exitCode);
                createdFailureReport = reportDirectory != null;
            }
            if (reportDirectory != null) {
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
                reportRegistry.register(run.id(), reportDirectory);
                run.appendLog("Report: " + reportDirectory);
            }
            if (!request.distributedExecution()) {
                deleteIfEmpty(executionReportsRoot);
            }
            run.complete(exitCode, reportDirectory);
            runningRunId.compareAndSet(run.id(), null);
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
        artifacts.put(evidenceDirectory.resolve("booking-db-audit.json"), "booking-db-audit.json");
        artifacts.forEach((source, fileName) -> {
            if (!Files.isRegularFile(source)) {
                return;
            }
            try {
                Files.copy(source, reportDirectory.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                run.appendLog("Booking artifact copy skipped for " + source + ": " + exception.getMessage());
            }
        });
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
        final Path reportRoot = archivedReportRoot(request);
        final Path reportDirectory = uniqueReportDirectory(reportRoot
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
        return commandBuilder.build(request, executionReportsRoot, description);
    }

    private void prepareGeneratedAccessTokens(
            final LoadTestRun run,
            final LoadTestRequest request
    ) throws IOException, InterruptedException {
        if (!request.generatesAccessTokensFile() || request.distributedExecution()) {
            return;
        }
        final List<String> command = accessTokenGenerationCommand(request);
        run.appendLog("Generating access token file before Gatling run.");
        run.appendLog("$ " + String.join(" ", redactSensitiveArguments(command)));

        final Process process = new ProcessBuilder(command)
                .directory(request.ticketProjectPath().toFile())
                .redirectErrorStream(true)
                .start();
        run.attachProcess(process);

        final int exitCode;
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    run.appendLog(line);
                }
            }

            exitCode = process.waitFor();
        } finally {
            run.clearProcess(process);
        }
        if (run.stopRequested()) {
            return;
        }
        if (exitCode != 0) {
            throw new IllegalStateException("Access token generation failed with exit code " + exitCode);
        }
    }

    private List<String> accessTokenGenerationCommand(final LoadTestRequest request) {
        final List<String> command = new ArrayList<>();
        command.add(gradleWrapper(request));
        command.add("-p");
        command.add("load-tests/gatling");
        command.add("generateAccessTokens");
        command.add("-Doutput=" + request.accessTokensFile());
        command.add("-DtokenCount=" + request.generatedAccessTokenCount());
        command.add("-DjwtSecret=" + request.jwtSecret());
        command.add("-DjwtIssuer=" + request.jwtIssuer());
        command.add("-DsyntheticMemberStartId=" + request.syntheticMemberStartId());
        command.add("-DsyntheticJwtRole=" + request.syntheticJwtRole());
        command.add("-DsyntheticTokenTtlSeconds=" + request.syntheticTokenTtlSeconds());
        return List.copyOf(command);
    }

    private String gradleWrapper(final LoadTestRequest request) {
        final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return request.ticketProjectPath().resolve(windows ? "gradlew.bat" : "gradlew").toString();
    }

    private Path resolveInputPath(final Path baseDirectory, final String value) {
        final Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return baseDirectory.resolve(path).toAbsolutePath().normalize();
    }

    private void validateRunnableProject(final LoadTestRequest request) {
        validateLoadTestsProject(request.ticketProjectPath());
        if (!request.distributedExecution()) {
            return;
        }
        final Path scriptPath = distributedCommandBuilder.scriptPath(request);
        if (!Files.isRegularFile(scriptPath)) {
            throw new IllegalArgumentException("Distributed script not found: " + scriptPath);
        }
    }

    private void validateLoadTestsProject(final Path ticketProjectPath) {
        if (!Files.exists(ticketProjectPath.resolve("gradlew.bat"))) {
            throw new IllegalArgumentException("gradlew.bat not found: " + ticketProjectPath);
        }
        if (!Files.isDirectory(ticketProjectPath.resolve("load-tests").resolve("gatling"))) {
            throw new IllegalArgumentException("Gatling load-tests project not found under: " + ticketProjectPath);
        }
    }

    private Path executionReportsRoot(final LoadTestRequest request, final UUID runId) {
        return request.ticketProjectPath().resolve("load-tests").resolve("gatling")
                .resolve("build").resolve("tmp").resolve("gatling-console-runs")
                .resolve(runId.toString())
                .toAbsolutePath().normalize();
    }

    private Optional<Path> parseDistributedRunDirectory(final String line) {
        final String marker = "Run dir:";
        final int markerIndex = line.indexOf(marker);
        if (markerIndex < 0) {
            return Optional.empty();
        }
        final String pathValue = line.substring(markerIndex + marker.length()).trim();
        if (pathValue.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(pathValue).toAbsolutePath().normalize());
    }

    private Optional<Path> resolveDistributedReportDirectory(
            final LoadTestRequest request,
            final Path parsedRunDirectory
    ) {
        if (parsedRunDirectory != null && Files.isDirectory(parsedRunDirectory)) {
            return Optional.of(parsedRunDirectory);
        }
        return latestDistributedRunDirectory(request);
    }

    private Optional<Path> latestDistributedRunDirectory(final LoadTestRequest request) {
        final Path root = distributedReportRoot(request);
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .max(Comparator.comparingLong(this::lastModified));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private Path distributedReportRoot(final LoadTestRequest request) {
        return request.reportsRoot().toAbsolutePath().normalize();
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
                .append(".cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(136px,1fr));gap:10px;margin:18px 0;}")
                .append(".card{border:1px solid #d7dee8;border-radius:8px;background:#fff;padding:12px}.label{color:#66758a;font-size:11px;font-weight:700}.value{margin-top:4px;font-size:20px;font-weight:760;}")
                .append(".table-wrap{overflow:auto;border:1px solid #d7dee8;border-radius:8px;background:#fff;}table{width:100%;border-collapse:collapse;white-space:nowrap;}")
                .append("th,td{border-bottom:1px solid #e5eaf0;padding:9px 10px;text-align:right;}th{background:#f8fafc;color:#66758a;font-size:12px;}td:first-child,th:first-child{text-align:left;}")
                .append(".status{font-weight:760}.SUCCESS{color:#067647}.FAILED{color:#b42318}.UNKNOWN{color:#b54708}.note{margin-top:10px;color:#66758a;font-size:12px;}")
                .append(".failure-preview{background:#fff;border:1px solid #d7dee8;border-radius:8px;margin:8px 0;padding:10px;}.failure-preview summary{cursor:pointer;font-weight:700;text-align:left;}")
                .append(".failure-preview pre{max-height:320px;white-space:pre-wrap;overflow:auto;margin:10px 0 0;}.status-code{font-weight:760;color:#b42318;}")
                .append("pre{padding:14px;border:1px solid #d7dee8;background:#fff;overflow:auto;}")
                .append("</style></head><body>")
                .append("<main>")
                .append("<h1>Distributed Gatling Summary</h1>")
                .append("<div class=\"muted path\">Run directory: ")
                .append(htmlEscape(runDirectory.toString()))
                .append("</div><div class=\"actions\">");
        appendFileLink(html, runDirectory, "summary.csv");
        appendFileLink(html, runDirectory, "summary.md");
        appendFileLink(html, runDirectory, "run-metadata.json");
        appendFileLink(html, runDirectory, "booking-summary.json");
        appendFileLink(html, runDirectory, "booking-results-merged.csv");
        appendFileLink(html, runDirectory, "booking-admissions-global.csv");
        appendFileLink(html, runDirectory, "booking-db-audit.json");
        html.append("</div>");

        if (!appendDistributedSummary(html, runDirectory, run)) {
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
        }

        appendFailureResponseBodies(html, runDirectory, run);

        html.append("<h2>Node reports</h2><ul>");
        try (Stream<Path> stream = Files.walk(runDirectory, 8)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("index.html"))
                    .filter(path -> !path.equals(index))
                    .sorted()
                    .forEach(path -> html.append("<li><a href=\"")
                            .append(htmlEscape(runDirectory.relativize(path).toString().replace('\\', '/')))
                            .append("\">")
                            .append(htmlEscape(runDirectory.relativize(path).toString()))
                            .append("</a></li>"));
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

    private boolean appendDistributedSummary(
            final StringBuilder html,
            final Path runDirectory,
            final LoadTestRun run
    ) {
        final Path summaryCsv = runDirectory.resolve("summary.csv");
        if (!Files.isRegularFile(summaryCsv)) {
            return false;
        }
        final List<Map<String, String>> rows;
        try {
            rows = readSummaryCsv(summaryCsv);
        } catch (IOException exception) {
            run.appendLog("Distributed summary table skipped: " + exception.getMessage());
            return false;
        }
        if (rows.isEmpty()) {
            return false;
        }

        final double total = rows.stream().mapToDouble(row -> numberValue(row, "TotalRequests")).sum();
        final double ok = rows.stream().mapToDouble(row -> numberValue(row, "OkRequests")).sum();
        final double ko = rows.stream().mapToDouble(row -> numberValue(row, "KoRequests")).sum();
        final double rps = rows.stream().mapToDouble(row -> numberValue(row, "RequestsPerSec")).sum();
        final double min = rows.stream()
                .mapToDouble(row -> numberValue(row, "MinMs"))
                .filter(value -> value > 0)
                .min()
                .orElse(0);
        final double max = rows.stream().mapToDouble(row -> numberValue(row, "MaxMs")).max().orElse(0);
        final double meanNumerator = rows.stream()
                .mapToDouble(row -> numberValue(row, "MeanMs") * numberValue(row, "TotalRequests"))
                .sum();
        final double mean = total > 0 ? meanNumerator / total : 0;
        final double koPercent = total > 0 ? ko * 100.0 / total : 0;

        html.append("<h2>Overall</h2><section class=\"cards\">");
        appendMetricCard(html, "Nodes", String.valueOf(rows.size()));
        appendMetricCard(html, "Total", formatDisplayNumber(total));
        appendMetricCard(html, "OK", formatDisplayNumber(ok));
        appendMetricCard(html, "KO", formatDisplayNumber(ko));
        appendMetricCard(html, "KO %", formatDisplayNumber(koPercent));
        appendMetricCard(html, "Cnt/s", formatDisplayNumber(rps));
        appendMetricCard(html, "Mean ms", formatDisplayNumber(mean));
        appendMetricCard(html, "Max ms", formatDisplayNumber(max));
        html.append("</section>");
        if (min > 0) {
            html.append("<p class=\"note\">Min ms: ")
                    .append(htmlEscape(formatDisplayNumber(min)))
                    .append(". p50/p75/p95/p99는 노드별 값입니다.</p>");
        }

        html.append("<h2>Node Metrics</h2><div class=\"table-wrap\"><table><thead><tr>")
                .append("<th>Node</th><th>Status</th><th>Total</th><th>OK</th><th>KO</th><th>KO %</th>")
                .append("<th>Cnt/s</th><th>Min</th><th>p50</th><th>p75</th><th>p95</th><th>p99</th>")
                .append("<th>Max</th><th>Mean</th><th>Report</th><th>Log</th></tr></thead><tbody>");
        for (Map<String, String> row : rows) {
            html.append("<tr>")
                    .append("<td>").append(htmlEscape(rowValue(row, "Node"))).append("</td>")
                    .append("<td class=\"status ").append(htmlEscape(rowValue(row, "Status"))).append("\">")
                    .append(htmlEscape(rowValue(row, "Status"))).append("</td>");
            appendMetricCell(html, row, "TotalRequests");
            appendMetricCell(html, row, "OkRequests");
            appendMetricCell(html, row, "KoRequests");
            appendMetricCell(html, row, "KoPercent");
            appendMetricCell(html, row, "RequestsPerSec");
            appendMetricCell(html, row, "MinMs");
            appendMetricCell(html, row, "P50Ms");
            appendMetricCell(html, row, "P75Ms");
            appendMetricCell(html, row, "P95Ms");
            appendMetricCell(html, row, "P99Ms");
            appendMetricCell(html, row, "MaxMs");
            appendMetricCell(html, row, "MeanMs");
            appendPathLinkCell(html, runDirectory, rowValue(row, "ReportPath"), "Report");
            appendPathLinkCell(html, runDirectory, rowValue(row, "LogPath"), "Log");
            html.append("</tr>");
        }
        html.append("</tbody></table></div>");
        return true;
    }

    private void appendFailureResponseBodies(
            final StringBuilder html,
            final Path runDirectory,
            final LoadTestRun run
    ) {
        final List<FailureResponseBody> bodies = findFailureResponseBodies(runDirectory, run);
        if (bodies.isEmpty()) {
            return;
        }

        html.append("<h2>Failure Response Bodies</h2>")
                .append("<p class=\"note\">")
                .append("`-DumpFailureBody`로 저장된 실패 응답입니다. Body 링크는 원본 HTML을 그대로 열고, Preview는 일부를 텍스트로 보여줍니다.")
                .append("</p>")
                .append("<div class=\"table-wrap\"><table><thead><tr>")
                .append("<th>Node</th><th>Status</th><th>Server</th><th>CF-Ray</th><th>CF Cache</th><th>Body</th><th>Meta</th>")
                .append("</tr></thead><tbody>");

        for (FailureResponseBody body : bodies) {
            html.append("<tr>")
                    .append("<td>").append(htmlEscape(body.node())).append("</td>")
                    .append("<td><span class=\"status-code\">").append(htmlEscape(body.status())).append("</span></td>")
                    .append("<td>").append(htmlEscape(body.server())).append("</td>")
                    .append("<td>").append(htmlEscape(body.cfRay())).append("</td>")
                    .append("<td>").append(htmlEscape(body.cfCacheStatus())).append("</td>")
                    .append("<td>");
            appendPathLink(html, runDirectory, body.bodyPath(), "Body");
            html.append("</td><td>");
            appendPathLink(html, runDirectory, body.metadataPath(), "Meta");
            html.append("</td></tr>");

            final String preview = failureBodyPreview(body.bodyPath(), run);
            if (!preview.isBlank()) {
                html.append("<tr><td colspan=\"7\"><details class=\"failure-preview\"><summary>")
                        .append(htmlEscape(body.node()))
                        .append(" / ")
                        .append(htmlEscape(body.bodyPath().getFileName().toString()))
                        .append(" preview</summary><pre>")
                        .append(htmlEscape(preview))
                        .append("</pre></details></td></tr>");
            }
        }

        html.append("</tbody></table></div>");
    }

    private List<FailureResponseBody> findFailureResponseBodies(final Path runDirectory, final LoadTestRun run) {
        if (!Files.isDirectory(runDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(runDirectory, 8)) {
            return stream.filter(Files::isRegularFile)
                    .filter(this::isFailureBodyHtml)
                    .sorted(Comparator.comparing(path -> runDirectory.relativize(path).toString()))
                    .map(path -> toFailureResponseBody(runDirectory, path, run))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException exception) {
            run.appendLog("Failure response body list skipped: " + exception.getMessage());
            return List.of();
        }
    }

    private boolean isFailureBodyHtml(final Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html")
                && path.toString().contains("failure-bodies");
    }

    private Optional<FailureResponseBody> toFailureResponseBody(
            final Path runDirectory,
            final Path bodyPath,
            final LoadTestRun run
    ) {
        final Path metadataPath = siblingWithExtension(bodyPath, ".txt");
        final Map<String, String> metadata = readFailureBodyMetadata(metadataPath, run);
        final Path relativePath = runDirectory.relativize(bodyPath);
        final String node = relativePath.getNameCount() > 0 ? relativePath.getName(0).toString() : "";
        return Optional.of(new FailureResponseBody(
                bodyPath,
                metadataPath,
                node,
                metadataValue(metadata, "status", statusFromFileName(bodyPath)),
                metadataValue(metadata, "server", ""),
                metadataValue(metadata, "cfRay", ""),
                metadataValue(metadata, "cfCacheStatus", "")
        ));
    }

    private Map<String, String> readFailureBodyMetadata(final Path metadataPath, final LoadTestRun run) {
        if (!Files.isRegularFile(metadataPath)) {
            return Map.of();
        }
        final Map<String, String> metadata = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(metadataPath, StandardCharsets.UTF_8)) {
                final int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                metadata.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        } catch (IOException exception) {
            run.appendLog("Failure response metadata skipped: " + exception.getMessage());
        }
        return metadata;
    }

    private Path siblingWithExtension(final Path path, final String extension) {
        final String fileName = path.getFileName().toString();
        final int dotIndex = fileName.lastIndexOf('.');
        final String baseName = dotIndex < 0 ? fileName : fileName.substring(0, dotIndex);
        return path.resolveSibling(baseName + extension);
    }

    private String metadataValue(
            final Map<String, String> metadata,
            final String key,
            final String defaultValue
    ) {
        return metadata.getOrDefault(key, defaultValue);
    }

    private String statusFromFileName(final Path bodyPath) {
        final String name = bodyPath.getFileName().toString();
        final String marker = "-status-";
        final int markerIndex = name.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        int endIndex = markerIndex + marker.length();
        while (endIndex < name.length() && Character.isDigit(name.charAt(endIndex))) {
            endIndex++;
        }
        return name.substring(markerIndex + marker.length(), endIndex);
    }

    private String failureBodyPreview(final Path bodyPath, final LoadTestRun run) {
        try {
            final String body = Files.readString(bodyPath, StandardCharsets.UTF_8);
            if (body.length() <= FAILURE_BODY_PREVIEW_LIMIT) {
                return body;
            }
            return body.substring(0, FAILURE_BODY_PREVIEW_LIMIT) + "\n... preview truncated ...";
        } catch (IOException exception) {
            run.appendLog("Failure response body preview skipped: " + exception.getMessage());
            return "";
        }
    }

    private List<Map<String, String>> readSummaryCsv(final Path summaryCsv) throws IOException {
        final List<String> lines = Files.readAllLines(summaryCsv, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            return List.of();
        }
        final List<String> headers = parseCsvLine(lines.getFirst());
        final List<Map<String, String>> rows = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            final List<String> values = parseCsvLine(lines.get(index));
            final Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                row.put(headers.get(column), column < values.size() ? values.get(column) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private List<String> parseCsvLine(final String line) {
        final List<String> values = new ArrayList<>();
        final StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            final char current = line.charAt(index);
            if (quoted && current == '"' && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                value.append('"');
                index++;
            } else if (current == '"') {
                quoted = !quoted;
            } else if (current == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        values.add(value.toString());
        return values;
    }

    private void appendMetricCard(final StringBuilder html, final String label, final String value) {
        html.append("<div class=\"card\"><div class=\"label\">")
                .append(htmlEscape(label))
                .append("</div><div class=\"value\">")
                .append(htmlEscape(value))
                .append("</div></div>");
    }

    private void appendMetricCell(
            final StringBuilder html,
            final Map<String, String> row,
            final String key
    ) {
        html.append("<td>").append(htmlEscape(formatDisplayNumber(rowValue(row, key)))).append("</td>");
    }

    private void appendPathLinkCell(
            final StringBuilder html,
            final Path runDirectory,
            final String value,
            final String label
    ) {
        final Optional<String> link = relativeLink(runDirectory, value);
        if (link.isEmpty()) {
            html.append("<td>-</td>");
            return;
        }
        html.append("<td><a href=\"")
                .append(htmlEscape(link.get()))
                .append("\">")
                .append(htmlEscape(label))
                .append("</a></td>");
    }

    private void appendPathLink(
            final StringBuilder html,
            final Path runDirectory,
            final Path path,
            final String label
    ) {
        if (!Files.isRegularFile(path)) {
            html.append("-");
            return;
        }
        final Optional<String> link = relativeLink(runDirectory, path.toString());
        if (link.isEmpty()) {
            html.append("-");
            return;
        }
        html.append("<a href=\"")
                .append(htmlEscape(link.get()))
                .append("\">")
                .append(htmlEscape(label))
                .append("</a>");
    }

    private Optional<String> relativeLink(final Path runDirectory, final String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            final Path path = Path.of(value).toAbsolutePath().normalize();
            if (!path.startsWith(runDirectory.toAbsolutePath().normalize())) {
                return Optional.empty();
            }
            return Optional.of(runDirectory.relativize(path).toString().replace('\\', '/'));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String rowValue(final Map<String, String> row, final String key) {
        return row.getOrDefault(key, "");
    }

    private double numberValue(final Map<String, String> row, final String key) {
        final String value = rowValue(row, key);
        if (value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String formatDisplayNumber(final String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return formatDisplayNumber(Double.parseDouble(value));
        } catch (NumberFormatException exception) {
            return value;
        }
    }

    private String formatDisplayNumber(final double value) {
        if (Math.abs(value - Math.round(value)) < 0.001) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void appendFileLink(final StringBuilder html, final Path directory, final String fileName) {
        if (Files.isRegularFile(directory.resolve(fileName))) {
            html.append("<a href=\"")
                    .append(htmlEscape(fileName))
                    .append("\">")
                    .append(htmlEscape(fileName))
                    .append("</a>");
        }
    }

    private String htmlEscape(final String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record FailureResponseBody(
            Path bodyPath,
            Path metadataPath,
            String node,
            String status,
            String server,
            String cfRay,
            String cfCacheStatus
    ) {
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
        final Path target = uniqueReportDirectory(archivedReportRoot(request)
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

    private Path archivedReportRoot(final LoadTestRequest request) {
        return request.reportsRoot();
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
            if (argument.equals("-JwtSecret")) {
                redacted.add(argument);
                redactNext = true;
                continue;
            }
            if (argument.startsWith("-DloginPassword=")) {
                redacted.add("-DloginPassword=****");
            } else if (argument.startsWith("-DjwtSecret=")) {
                redacted.add("-DjwtSecret=****");
            } else if (argument.startsWith("-DaccessTokens=")) {
                redacted.add("-DaccessTokens=****");
            } else if (argument.startsWith("-DadmissionTokenSecret=")) {
                redacted.add("-DadmissionTokenSecret=****");
            } else if (argument.startsWith("-DadmissionTokens=")) {
                redacted.add("-DadmissionTokens=****");
            } else {
                redacted.add(argument);
            }
        }
        return List.copyOf(redacted);
    }
}
