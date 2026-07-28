package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadTestRunJsonTest {

    @Test
    void listJsonExcludesVerboseRunLog() {
        final LoadTestRun run = new LoadTestRun(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                LoadTestRequest.fromForm(Map.of())
        );

        run.appendLog("large log line");

        final String json = LoadTestRun.listJson(List.of(run));

        assertTrue(json.contains("\"id\":\"00000000-0000-0000-0000-000000000001\""));
        assertFalse(json.contains("\"log\""));
        assertFalse(json.contains("large log line"));
    }

    @Test
    void detailJsonIncludesRunLog() {
        final LoadTestRun run = new LoadTestRun(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                LoadTestRequest.fromForm(Map.of())
        );

        run.appendLog("detail log line");

        final String json = run.toJson();

        assertTrue(json.contains("\"log\""));
        assertTrue(json.contains("detail log line"));
    }

    @Test
    void detailLogRedactsBearerTokens() {
        final LoadTestRun run = new LoadTestRun(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                LoadTestRequest.fromForm(Map.of())
        );

        run.appendLog("{Authorization: Bearer header.payload.signature}, next");

        assertTrue(run.log().contains("Authorization: Bearer ****"));
        assertFalse(run.log().contains("header.payload.signature"));
        assertTrue(run.log().contains("{Authorization: Bearer ****}, next"));
    }
}
