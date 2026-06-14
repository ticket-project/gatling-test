//package com.ticket.gatling.console;
//
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.io.TempDir;
//
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.UUID;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//
//class ReportRegistryTest {
//
//    @TempDir
//    Path tempDir;
//
//    @Test
//    void resolvesOnlyRegisteredReportFiles() throws Exception {
//        final ReportRegistry registry = new ReportRegistry();
//        final UUID runId = UUID.randomUUID();
//        final Path reportDir = tempDir.resolve("report");
//        Files.createDirectories(reportDir.resolve("js"));
//        Files.writeString(reportDir.resolve("index.html"), "ok");
//        Files.writeString(reportDir.resolve("js").resolve("stats.js"), "stats");
//
//        registry.register(runId, reportDir);
//
//        assertEquals(reportDir.resolve("index.html"), registry.resolve(runId, "index.html").orElseThrow());
//        assertEquals(reportDir.resolve("js").resolve("stats.js"), registry.resolve(runId, "js/stats.js").orElseThrow());
//        assertTrue(registry.resolve(runId, "../secret.txt").isEmpty());
//    }
//}
