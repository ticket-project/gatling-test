//package com.ticket.gatling.console;
//
//import org.junit.jupiter.api.Test;
//
//import java.nio.file.Path;
//import java.util.Map;
//import java.util.UUID;
//
//import static org.junit.jupiter.api.Assertions.assertTrue;
//
//class LoadTestRunTest {
//
//    @Test
//    void includesLocalReportPathInJsonAfterCompletion() {
//        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of());
//        final LoadTestRun run = new LoadTestRun(UUID.fromString("00000000-0000-0000-0000-000000000001"), request);
//        final Path reportDirectory = Path.of("C:/reports/queue-enter-001");
//
//        run.complete(0, reportDirectory);
//
//        final String json = run.toJson();
//        assertTrue(json.contains("\"reportPath\":\"C:\\\\reports\\\\queue-enter-001\\\\index.html\""));
//        assertTrue(json.contains("\"durationMillis\":"));
//    }
//}
