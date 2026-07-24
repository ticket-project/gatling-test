package com.ticket.gatling.console;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class LoadTestRun {
    public enum Status {
        RUNNING,
        STOPPED,
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
    private volatile boolean stopRequested;
    private Process currentProcess;

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

    synchronized void attachProcess(final Process process) {
        currentProcess = process;
        if (stopRequested) {
            terminateProcessTree(process);
        }
    }

    synchronized void clearProcess(final Process process) {
        if (currentProcess == process) {
            currentProcess = null;
        }
    }

    synchronized boolean requestStop() {
        if (status != Status.RUNNING) {
            return false;
        }
        if (!stopRequested) {
            stopRequested = true;
            appendLog("Stop requested by console.");
        }
        if (currentProcess != null) {
            terminateProcessTree(currentProcess);
        }
        return true;
    }

    boolean stopRequested() {
        return stopRequested;
    }

    public void complete(final int exitCode, final Path reportDirectory) {
        this.exitCode = exitCode;
        this.reportDirectory = reportDirectory;
        this.finishedAt = Instant.now();
        this.status = stopRequested ? Status.STOPPED : (exitCode == 0 ? Status.SUCCEEDED : Status.FAILED);
    }

    private void terminateProcessTree(final Process process) {
        final List<ProcessHandle> handles = new ArrayList<>(process.toHandle().descendants().toList());
        handles.reversed().forEach(ProcessHandle::destroy);
        process.destroy();
        waitForExit(process.toHandle());
        handles.reversed().forEach(handle -> {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private void waitForExit(final ProcessHandle handle) {
        try {
            handle.onExit().get(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Forced termination below handles stubborn processes.
        }
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

    private String toListJson() {
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
                + "\"reportPath\":" + Json.nullable(reportPath)
                + "}";
    }

    public static String listJson(final List<LoadTestRun> runs) {
        return runs.stream()
                .map(LoadTestRun::toListJson)
                .reduce((left, right) -> left + "," + right)
                .map(value -> "[" + value + "]")
                .orElse("[]");
    }
}
