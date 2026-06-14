package com.ticket.gatling.console;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class LoadTestRun {
    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    private final UUID id;
    private final LoadTestRequest request;
    private final Instant startedAt;
    private final StringBuilder log = new StringBuilder();
    private volatile Status status = Status.RUNNING;
    private volatile int exitCode = -1;
    private volatile Path reportDirectory;
    private volatile Instant finishedAt;

    public LoadTestRun(final UUID id, final LoadTestRequest request) {
        this.id = id;
        this.request = request;
        this.startedAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public Status status() {
        return status;
    }

    public Path reportDirectory() {
        return reportDirectory;
    }

    public synchronized void appendLog(final String line) {
        log.append(line).append(System.lineSeparator());
    }

    public synchronized String log() {
        return log.toString();
    }

    public void complete(final int exitCode, final Path reportDirectory) {
        this.exitCode = exitCode;
        this.reportDirectory = reportDirectory;
        this.finishedAt = Instant.now();
        this.status = exitCode == 0 ? Status.SUCCEEDED : Status.FAILED;
    }

    public String toJson() {
        final String reportUrl = reportDirectory == null ? null : "/reports/" + id + "/index.html";
        final String reportPath = reportDirectory == null ? null : reportDirectory.resolve("index.html").toString();
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"simulation\":\"" + Json.escape(request.simulationType().label()) + "\","
                + "\"status\":\"" + status + "\","
                + "\"startedAt\":\"" + startedAt + "\","
                + "\"finishedAt\":" + Json.nullable(finishedAt == null ? null : finishedAt.toString()) + ","
                + "\"exitCode\":" + exitCode + ","
                + "\"reportUrl\":" + Json.nullable(reportUrl) + ","
                + "\"reportPath\":" + Json.nullable(reportPath) + ","
                + "\"log\":\"" + Json.escape(log()) + "\""
                + "}";
    }

    public static String listJson(final List<LoadTestRun> runs) {
        return runs.stream()
                .map(LoadTestRun::toJson)
                .reduce((left, right) -> left + "," + right)
                .map(value -> "[" + value + "]")
                .orElse("[]");
    }
}
