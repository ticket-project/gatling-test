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
    private final GatlingCommandBuilder commandBuilder = new GatlingCommandBuilder();
    private final DistributedGatlingCommandBuilder distributedCommandBuilder = new DistributedGatlingCommandBuilder();
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
        validateDistributedExecution(request);
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
        if (request.simulationType() != SimulationType.CDN_PUBLIC_STATE
                && request.simulationType() != SimulationType.LEGACY_QUEUE_STATUS
                && request.simulationType() != SimulationType.QUEUE_JOIN_ONLY) {
            throw new IllegalArgumentException(
                    "Distributed execution supports only Queue Join Only, CDN Public State and Legacy Queue Status"
            );
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

    public synchronized List<LoadTestRun> runs() {
        return new ArrayList<>(runs.values()).reversed();
    }

    public synchronized Optional<LoadTestRun> find(final UUID runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    private void execute(final LoadTestRun run, final LoadTestRequest request) {
        final Path executionReportsRoot = executionReportsRoot(request, run.id());
        final Set<Path> beforeReports = listReportDirectories(executionReportsRoot);
        Path distributedRunDirectory = null;
        int exitCode = -1;
        try {
            validateRunnableProject(request);
            prepareGeneratedAccessTokens(run, request);
            if (!request.distributedExecution()) {
                Files.createDirectories(executionReportsRoot);
            }
            final List<String> command = buildCommand(request, executionReportsRoot);
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
                    if (request.distributedExecution()) {
                        distributedRunDirectory = parseDistributedRunDirectory(line).orElse(distributedRunDirectory);
                    }
                }
            }
            exitCode = process.waitFor();
        } catch (Exception exception) {
            run.appendLog("ERROR: " + exception.getMessage());
        } finally {
            Path reportDirectory = request.distributedExecution()
                    ? resolveDistributedReportDirectory(request, distributedRunDirectory).orElse(null)
                    : detectReportDirectory(executionReportsRoot, beforeReports).orElse(null);
            if (reportDirectory != null) {
                if (request.distributedExecution()) {
                    writeDistributedIndex(reportDirectory, run);
                } else {
                    reportDirectory = renameReportDirectory(reportDirectory, request, run);
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

    private List<String> buildCommand(final LoadTestRequest request, final Path executionReportsRoot) {
        if (request.distributedExecution()) {
            return distributedCommandBuilder.build(request);
        }
        return commandBuilder.build(request, executionReportsRoot);
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

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                run.appendLog(line);
            }
        }

        final int exitCode = process.waitFor();
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
        final String directoryName = switch (request.simulationType()) {
            case CDN_PUBLIC_STATE -> "distributed-results";
            case QUEUE_JOIN_ONLY -> "distributed-results-join";
            default -> "distributed-results-legacy";
        };
        final Path root = request.ticketProjectPath().resolve(directoryName).toAbsolutePath().normalize();
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

    private void writeDistributedIndex(final Path runDirectory, final LoadTestRun run) {
        final Path index = runDirectory.resolve("index.html");
        final StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">")
                .append("<title>Distributed Gatling Summary</title>")
                .append("<style>")
                .append("body{font-family:Segoe UI,system-ui,sans-serif;margin:24px;color:#17202c;background:#f8fafc;}")
                .append("h1{font-size:22px;margin:0 0 16px;}h2{font-size:16px;margin:24px 0 10px;}")
                .append("a{color:#1d4ed8;}pre{padding:14px;border:1px solid #d7dee8;background:#fff;overflow:auto;}")
                .append("li{margin:6px 0;}")
                .append("</style></head><body>")
                .append("<h1>Distributed Gatling Summary</h1>")
                .append("<p>Run directory: <code>")
                .append(htmlEscape(runDirectory.toString()))
                .append("</code></p><ul>");
        appendFileLink(html, runDirectory, "summary.csv");
        appendFileLink(html, runDirectory, "summary.md");
        html.append("</ul>");

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
        html.append("</ul></body></html>");

        try {
            Files.writeString(index, html.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            run.appendLog("Distributed index write failed: " + exception.getMessage());
        }
    }

    private void appendFileLink(final StringBuilder html, final Path directory, final String fileName) {
        if (Files.isRegularFile(directory.resolve(fileName))) {
            html.append("<li><a href=\"")
                    .append(htmlEscape(fileName))
                    .append("\">")
                    .append(htmlEscape(fileName))
                    .append("</a></li>");
        }
    }

    private String htmlEscape(final String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
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
