package com.ticket.gatling.console;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ConsoleServer {
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=UTF-8",
            "css", "text/css; charset=UTF-8",
            "js", "application/javascript; charset=UTF-8",
            "json", "application/json; charset=UTF-8",
            "txt", "text/plain; charset=UTF-8",
            "csv", "text/plain; charset=UTF-8",
            "md", "text/plain; charset=UTF-8",
            "png", "image/png",
            "svg", "image/svg+xml"
    );

    private static final Path LOAD_TESTS_ROOT = Path.of(LoadTestRequest.DEFAULT_LOAD_TESTS_PATH);
    private static final Path RESULTS_ROOT = LOAD_TESTS_ROOT.resolve("distributed-results-join");

    private final HttpServer server;
    private final LoadTestService loadTestService;

    public ConsoleServer(final int port, final LoadTestService loadTestService) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.loadTestService = loadTestService;
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
    }

    public void start() {
        server.start();
    }

    private void handle(final HttpExchange exchange) throws IOException {
        try {
            final String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/environments")) {
                handleEnvironments(exchange);
                return;
            }
            if (path.equals("/api/simulations")) {
                handleSimulations(exchange);
                return;
            }
            if (path.equals("/api/runs")) {
                handleRuns(exchange);
                return;
            }
            if (path.startsWith("/api/runs/")) {
                handleRunApi(exchange, path);
                return;
            }
            if (path.startsWith("/reports/")) {
                handleReport(exchange, path);
                return;
            }
            if (path.equals("/api/capacity-runs")) {
                requireMethod(exchange, "GET");
                writeJson(exchange, 200, CapacityRunHistory.json(RESULTS_ROOT));
                return;
            }
            if (path.equals("/api/member-ids")) {
                handleMemberIds(exchange);
                return;
            }
            if (path.startsWith("/results/")) {
                handleResultFile(exchange, path.substring("/results/".length()));
                return;
            }
            handleStatic(exchange, path);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            writeJson(exchange, 400, "{\"error\":\"" + Json.escape(exception.getMessage()) + "\"}");
        } catch (Exception exception) {
            writeJson(exchange, 500, "{\"error\":\"" + Json.escape(exception.getMessage()) + "\"}");
        }
    }

    private void handleEnvironments(final HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        writeJson(exchange, 200, environmentsJson(
                TargetEnvironmentCatalog.load(Path.of(TargetEnvironmentCatalog.DEFAULT_FILE_NAME))
        ));
    }

    static String environmentsJson(final List<TargetEnvironment> targets) {
        return targets.stream()
                .map(target -> "{"
                        + "\"key\":\"" + Json.escape(target.key()) + "\","
                        + "\"label\":\"" + Json.escape(target.label()) + "\","
                        + "\"coreBaseUrl\":\"" + Json.escape(target.coreBaseUrl()) + "\","
                        + "\"queueBaseUrl\":\"" + Json.escape(target.queueBaseUrl()) + "\""
                        + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private void handleSimulations(final HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        final String json = Arrays.stream(SimulationType.values())
                .map(type -> "{"
                        + "\"key\":\"" + type.key() + "\","
                        + "\"label\":\"" + Json.escape(type.label()) + "\","
                        + "\"usesStatusPolling\":" + type.usesStatusPolling() + ","
                        + "\"usesAccessTokens\":" + type.usesAccessTokens() + ","
                        + "\"usesBookingFeeder\":" + type.usesBookingFeeder() + ","
                        + "\"usesCoreBookingFlow\":" + type.usesCoreBookingFlow() + ","
                        + "\"usesQueueBaseUrl\":" + type.usesQueueBaseUrl() + ","
                        + "\"usesExistingMemberIds\":" + type.usesExistingMemberIds()
                        + "}")
                .collect(Collectors.joining(",", "[", "]"));
        writeJson(exchange, 200, json);
    }

    private void handleRuns(final HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 200, LoadTestRun.listJson(loadTestService.runs()));
            return;
        }
        requireMethod(exchange, "POST");
        final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        final LoadTestRequest request = LoadTestRequest.fromForm(FormParser.parse(body));
        final LoadTestRun run = loadTestService.start(request);
        writeJson(exchange, 202, run.toJson());
    }

    private void handleRunApi(final HttpExchange exchange, final String path) throws IOException {
        final String remaining = path.substring("/api/runs/".length());
        final String openFolderSuffix = "/open-report-folder";
        final String stopSuffix = "/stop";
        if (remaining.endsWith(openFolderSuffix)) {
            handleOpenReportFolder(exchange, remaining.substring(0, remaining.length() - openFolderSuffix.length()));
            return;
        }
        if (remaining.endsWith(stopSuffix)) {
            handleStopRun(exchange, remaining.substring(0, remaining.length() - stopSuffix.length()));
            return;
        }
        handleRunDetail(exchange, remaining);
    }

    private void handleRunDetail(final HttpExchange exchange, final String runIdValue) throws IOException {
        requireMethod(exchange, "GET");
        final UUID runId = UUID.fromString(runIdValue);
        final LoadTestRun run = loadTestService.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        writeJson(exchange, 200, run.toJson());
    }

    private void handleStopRun(final HttpExchange exchange, final String runIdValue) throws IOException {
        requireMethod(exchange, "POST");
        final UUID runId = UUID.fromString(runIdValue);
        final LoadTestRun run = loadTestService.stop(runId);
        writeJson(exchange, 202, run.toJson());
    }

    private void handleOpenReportFolder(final HttpExchange exchange, final String runIdValue) throws IOException {
        requireMethod(exchange, "POST");
        final UUID runId = UUID.fromString(runIdValue);
        final LoadTestRun run = loadTestService.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        final Path reportDirectory = run.reportDirectory();
        if (reportDirectory == null) {
            throw new IllegalStateException("Report folder is not available yet: " + runId);
        }
        if (!Files.isDirectory(reportDirectory)) {
            throw new IllegalArgumentException("Report folder not found: " + reportDirectory);
        }
        Desktop.getDesktop().open(reportDirectory.toFile());
        writeJson(exchange, 200, "{\"opened\":true}");
    }

    private void handleReport(final HttpExchange exchange, final String path) throws IOException {
        requireMethod(exchange, "GET");
        final String remaining = path.substring("/reports/".length());
        final int slash = remaining.indexOf('/');
        if (slash < 0) {
            redirect(exchange, "/reports/" + remaining + "/index.html");
            return;
        }
        final UUID runId = UUID.fromString(remaining.substring(0, slash));
        final String relativePath = remaining.substring(slash + 1).isBlank() ? "index.html" : remaining.substring(slash + 1);
        final Path reportDirectory = loadTestService.find(runId).map(LoadTestRun::reportDirectory).orElse(null);
        final Path resolved = reportDirectory == null ? null : reportDirectory.resolve(relativePath).normalize();
        if (resolved == null || !resolved.startsWith(reportDirectory) || !Files.isRegularFile(resolved)) {
            writeText(exchange, 404, "Not found", "text/plain; charset=UTF-8");
            return;
        }
        writeBytes(exchange, 200, Files.readAllBytes(resolved), contentType(resolved));
    }

    private void handleMemberIds(final HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        final String file = FormParser.parse(exchange.getRequestURI().getRawQuery())
                .getOrDefault("file", List.of("")).getFirst();
        final long count = file.isBlank() ? -1 : CapacityRunHistory.countMemberIds(LOAD_TESTS_ROOT.resolve(file));
        writeJson(exchange, 200, "{\"count\":" + count + "}");
    }

    /** 콘솔을 다시 시작한 뒤에도 수용량 패널에서 지난 실행의 리포트를 열 수 있게 결과 폴더의 파일을 내준다. */
    private void handleResultFile(final HttpExchange exchange, final String relativePath) throws IOException {
        requireMethod(exchange, "GET");
        final Path resolved = RESULTS_ROOT.resolve(relativePath).normalize();
        if (!resolved.startsWith(RESULTS_ROOT) || !Files.isRegularFile(resolved)) {
            writeText(exchange, 404, "Not found", "text/plain; charset=UTF-8");
            return;
        }
        writeBytes(exchange, 200, Files.readAllBytes(resolved), contentType(resolved));
    }

    private void handleStatic(final HttpExchange exchange, final String path) throws IOException {
        requireMethod(exchange, "GET");
        if (!path.equals("/") && !path.equals("/index.html")) {
            writeText(exchange, 404, "Not found", "text/plain; charset=UTF-8");
            return;
        }
        try (InputStream stream = getClass().getResourceAsStream("/static/index.html")) {
            if (stream == null) {
                writeText(exchange, 500, "index.html missing", "text/plain; charset=UTF-8");
                return;
            }
            writeBytes(exchange, 200, stream.readAllBytes(), "text/html; charset=UTF-8");
        }
    }

    private void requireMethod(final HttpExchange exchange, final String method) {
        if (!method.equals(exchange.getRequestMethod())) {
            throw new IllegalArgumentException("Method not allowed");
        }
    }

    private void redirect(final HttpExchange exchange, final String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private void writeJson(final HttpExchange exchange, final int statusCode, final String body) throws IOException {
        writeText(exchange, statusCode, body, "application/json; charset=UTF-8");
    }

    private void writeText(
            final HttpExchange exchange,
            final int statusCode,
            final String body,
            final String contentType
    ) throws IOException {
        writeBytes(exchange, statusCode, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private void writeBytes(
            final HttpExchange exchange,
            final int statusCode,
            final byte[] body,
            final String contentType
    ) throws IOException {
        final Map<String, java.util.List<String>> headers = exchange.getResponseHeaders();
        headers.put("Content-Type", java.util.List.of(contentType));
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private String contentType(final Path path) {
        final String name = path.getFileName().toString().toLowerCase();
        return CONTENT_TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1), "application/octet-stream");
    }
}
