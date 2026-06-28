package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributedReportIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void rendersFailureResponseBodyLinksAndPreview() throws Exception {
        final Path runDirectory = tempDir.resolve("20260627-150000");
        final Path failureBody = runDirectory
                .resolve("ubuntu_3_35_194_27")
                .resolve("failure-bodies")
                .resolve("queue-join-1-status-503.html");
        Files.createDirectories(failureBody.getParent());
        Files.writeString(failureBody, "<html>cloudflare 503</html>", StandardCharsets.UTF_8);
        Files.writeString(
                failureBody.resolveSibling("queue-join-1-status-503.txt"),
                """
                        status=503
                        server=cloudflare
                        cfRay=abc123
                        cfCacheStatus=
                        """,
                StandardCharsets.UTF_8
        );

        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "ticketProjectPath", List.of(tempDir.toString()),
                "executionMode", List.of("distributed"),
                "simulation", List.of("queue-join-only")
        ));
        final LoadTestRun run = new LoadTestRun(UUID.randomUUID(), request);

        final Method method = LoadTestService.class.getDeclaredMethod(
                "writeDistributedIndex",
                Path.class,
                LoadTestRun.class
        );
        method.setAccessible(true);
        method.invoke(new LoadTestService(new ReportRegistry()), runDirectory, run);

        final String indexHtml = Files.readString(runDirectory.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(indexHtml.contains("Failure Response Bodies"));
        assertTrue(indexHtml.contains("ubuntu_3_35_194_27"));
        assertTrue(indexHtml.contains("queue-join-1-status-503.html"));
        assertTrue(indexHtml.contains("queue-join-1-status-503.txt"));
        assertTrue(indexHtml.contains("cloudflare"));
        assertTrue(indexHtml.contains("&lt;html&gt;cloudflare 503&lt;/html&gt;"));
    }
}
