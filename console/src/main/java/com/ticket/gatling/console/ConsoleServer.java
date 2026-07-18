package com.ticket.gatling.console;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

public class ConsoleServer {
    private final HttpServer server;
    private final LoadTestService loadTestService;
    private final ReportRegistry reportRegistry;
    private final LocalFolderOpener localFolderOpener;

    public ConsoleServer(
            final int port,
            final LoadTestService loadTestService,
            final ReportRegistry reportRegistry
    ) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.loadTestService = loadTestService;
        this.reportRegistry = reportRegistry;
        this.localFolderOpener = new LocalFolderOpener();
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
    }

    public void start() {
        server.start();
    }

    private void handle(final HttpExchange exchange) throws IOException {
        try {
            final String path = exchange.getRequestURI().getPath();
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
            handleStatic(exchange, path);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            writeJson(exchange, 400, "{\"error\":\"" + Json.escape(exception.getMessage()) + "\"}");
        } catch (Exception exception) {
            writeJson(exchange, 500, "{\"error\":\"" + Json.escape(exception.getMessage()) + "\"}");
        }
    }

    private void handleSimulations(final HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        final String json = Arrays.stream(SimulationType.values())
                .map(type -> "{"
                        + "\"key\":\"" + type.key() + "\","
                        + "\"label\":\"" + Json.escape(type.label()) + "\","
                        + "\"defaultBaseUrl\":\"" + Json.escape(type.defaultBaseUrl()) + "\","
                        + "\"usesSeatIds\":" + type.usesSeatIds() + ","
                        + "\"usesAdmissionTokens\":" + type.usesAdmissionTokens() + ","
                        + "\"usesStatusPolling\":" + type.usesStatusPolling() + ","
                        + "\"usesAccessTokens\":" + type.usesAccessTokens() + ","
                        + "\"usesBookingFeeder\":" + type.usesBookingFeeder() + ","
                        + "\"usesQueueBaseUrl\":" + type.usesQueueBaseUrl() + ","
                        + "\"bookingScenario\":\"" + Json.escape(type.bookingScenario()) + "\""
                        + "}")
                .reduce((left, right) -> left + "," + right)
                .map(value -> "[" + value + "]")
                .orElse("[]");
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
        if (remaining.endsWith(openFolderSuffix)) {
            handleOpenReportFolder(exchange, remaining.substring(0, remaining.length() - openFolderSuffix.length()));
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

    private void handleOpenReportFolder(final HttpExchange exchange, final String runIdValue) throws IOException {
        requireMethod(exchange, "POST");
        final UUID runId = UUID.fromString(runIdValue);
        final LoadTestRun run = loadTestService.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        final Path reportDirectory = run.reportDirectory();
        if (reportDirectory == null) {
            throw new IllegalStateException("Report folder is not available yet: " + runId);
        }
        localFolderOpener.open(reportDirectory);
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
        final String relativePath = remaining.substring(slash + 1);
        final Optional<Path> resolved = reportRegistry.resolve(runId, relativePath);
        if (resolved.isEmpty() || !Files.isRegularFile(resolved.get())) {
            writeText(exchange, 404, "Not found", "text/plain; charset=UTF-8");
            return;
        }
        final byte[] bytes = Files.readAllBytes(resolved.get());
        writeBytes(exchange, 200, bytes, contentType(resolved.get()));
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
        if (name.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }
        if (name.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }
        if (name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".md")) {
            return "text/plain; charset=UTF-8";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }
}
