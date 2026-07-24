package com.ticket.gatling.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class DistributedRunStopper {
    private static final long SSH_TIMEOUT_SECONDS = 15;

    void stop(
            final LoadTestRequest request,
            final UUID runId,
            final Consumer<String> logger
    ) {
        final List<String> failedHosts = new ArrayList<>();
        for (String host : request.distributedHostList()) {
            logger.accept("Stopping remote Gatling process: " + host);
            try {
                stopHost(request, runId, host);
                logger.accept("Remote Gatling process stopped: " + host);
            } catch (Exception exception) {
                failedHosts.add(host);
                logger.accept("Remote stop failed for " + host + ": " + exception.getMessage());
            }
        }
        if (!failedHosts.isEmpty()) {
            throw new IllegalStateException("Remote stop failed: " + String.join(", ", failedHosts));
        }
    }

    private void stopHost(
            final LoadTestRequest request,
            final UUID runId,
            final String host
    ) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(
                sshExecutable(),
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
                "-o", "StrictHostKeyChecking=accept-new",
                "-i", request.sshKeyPath().toString(),
                host,
                remoteStopCommand(runId)
        ).redirectErrorStream(true).start();

        if (!process.waitFor(SSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("SSH stop timed out");
        }
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException(output.isBlank() ? "SSH exit code " + process.exitValue() : output);
        }
    }

    static String remoteStopCommand(final UUID runId) {
        final String value = runId.toString();
        final String pattern = "[" + value.charAt(0) + "]" + value.substring(1);
        return "pgrep -f '" + pattern + "' | xargs -r kill -TERM; "
                + "sleep 1; "
                + "pgrep -f '" + pattern + "' | xargs -r kill -KILL; "
                + "sleep 1; "
                + "if pgrep -f '" + pattern + "' >/dev/null; "
                + "then echo remote-stop-failed; exit 1; "
                + "else echo remote-stop-ok; fi";
    }

    private String sshExecutable() {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            return "ssh";
        }
        final String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        final List<Path> candidates = List.of(
                Path.of(systemRoot, "System32", "OpenSSH", "ssh.exe"),
                Path.of("C:\\Windows\\System32\\OpenSSH\\ssh.exe"),
                Path.of("C:\\Windows\\Sysnative\\OpenSSH\\ssh.exe")
        );
        return candidates.stream()
                .filter(Files::isRegularFile)
                .map(Path::toString)
                .findFirst()
                .orElse("ssh.exe");
    }
}
