package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadTestServiceFailureReportTest {

    @TempDir
    Path tempDir;

    @Test
    void createsReportFolderWhenRunFailsBeforeGatlingReportIsGenerated() throws Exception {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of(tempDir.toString()),
                "simulation", List.of("cdn-public-state")
        ));
        final LoadTestService service = new LoadTestService(new ReportRegistry());

        final LoadTestRun run = service.start(request);
        waitUntilFinished(run);

        assertEquals(LoadTestRun.Status.FAILED, run.status());
        assertNotNull(run.reportDirectory());
        assertTrue(Files.isRegularFile(run.reportDirectory().resolve("index.html")));
        assertTrue(Files.isRegularFile(run.reportDirectory().resolve("run.log")));
        assertTrue(run.reportDirectory().getFileName().toString().contains("failed"));
    }

    @Test
    void createsLocalQueueJoinFailureFolderUnderDistributedResultsJoin() throws Exception {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of(tempDir.toString()),
                "simulation", List.of("queue-join-only"),
                "accessTokenMode", List.of("synthetic-jwt")
        ));
        final LoadTestService service = new LoadTestService(new ReportRegistry());

        final LoadTestRun run = service.start(request);
        waitUntilFinished(run);

        assertEquals(LoadTestRun.Status.FAILED, run.status());
        assertNotNull(run.reportDirectory());
        assertEquals(tempDir.resolve("distributed-results-join"), run.reportDirectory().getParent());
        assertTrue(Files.isRegularFile(run.reportDirectory().resolve("index.html")));
        assertTrue(Files.isRegularFile(run.reportDirectory().resolve("run.log")));
    }

    @Test
    void createsDistributedJoinFailureFolderWhenDistributedRunFailsBeforeScriptCreatesRunDirectory() throws Exception {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of(tempDir.toString()),
                "executionMode", List.of("distributed"),
                "simulation", List.of("queue-join-only"),
                "accessTokenMode", List.of("synthetic-jwt")
        ));
        final LoadTestService service = new LoadTestService(new ReportRegistry());

        final LoadTestRun run = service.start(request);
        waitUntilFinished(run);

        assertEquals(LoadTestRun.Status.FAILED, run.status());
        assertNotNull(run.reportDirectory());
        assertEquals(tempDir.resolve("distributed-results-join"), run.reportDirectory().getParent());
        assertTrue(Files.isRegularFile(run.reportDirectory().resolve("index.html")));
        assertTrue(Files.isRegularFile(run.reportDirectory().resolve("run.log")));
    }

    private void waitUntilFinished(final LoadTestRun run) throws InterruptedException {
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (run.status() == LoadTestRun.Status.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
