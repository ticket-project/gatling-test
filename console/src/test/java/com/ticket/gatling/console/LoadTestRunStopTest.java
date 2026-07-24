package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadTestRunStopTest {

    @Test
    void requestStopTerminatesAttachedProcessAndCompletesAsStopped() throws Exception {
        final LoadTestRun run = new LoadTestRun(
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                LoadTestRequest.fromForm(Map.of())
        );
        final Process process = new ProcessBuilder(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                SleepProbe.class.getName())
                .redirectErrorStream(true)
                .start();

        run.attachProcess(process);

        assertTrue(process.isAlive());
        assertTrue(run.requestStop());
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));

        run.clearProcess(process);
        run.complete(process.exitValue(), null);

        assertEquals(LoadTestRun.Status.STOPPED, run.status());
        assertTrue(run.log().contains("Stop requested by console."));
    }

    @Test
    void requestStopReturnsFalseAfterRunHasCompleted() {
        final LoadTestRun run = new LoadTestRun(
                UUID.fromString("00000000-0000-0000-0000-000000000102"),
                LoadTestRequest.fromForm(Map.of())
        );

        run.complete(0, null);

        assertFalse(run.requestStop());
        assertEquals(LoadTestRun.Status.SUCCEEDED, run.status());
    }

    private static String javaExecutable() {
        final boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    public static final class SleepProbe {
        public static void main(final String[] args) throws Exception {
            Thread.sleep(60_000);
        }
    }
}