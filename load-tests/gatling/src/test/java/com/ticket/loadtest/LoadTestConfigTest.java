package com.ticket.loadtest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadTestConfigTest {

    private static final Path CONFIG_SOURCE = Path.of(
            "src/gatling/java/com/ticket/loadtest/LoadTestConfig.java"
    );

    @Test
    void defaultsBaseUrlToLegacyQueueVmContextPath() throws IOException {
        final String source = Files.readString(CONFIG_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("case BASE_URL -> \"http://52.237.82.8:18090/legacy-queue\";"));
    }

    @Test
    void exposesStatusPollPauseJitterWindow() throws IOException {
        final String source = Files.readString(CONFIG_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("public static Duration statusPollPauseMin()"));
        assertTrue(source.contains("public static Duration statusPollPauseMax()"));
        assertTrue(source.contains("STATUS_POLL_PAUSE_JITTER_SECONDS"));
        assertTrue(source.contains("case STATUS_POLL_PAUSE_JITTER_SECONDS -> \"statusPollPauseJitterSeconds\";"));
        assertTrue(source.contains("case STATUS_POLL_PAUSE_JITTER_SECONDS -> \"0\";"));
    }

    @Test
    void supportsAccessTokenFileInTokenMode() throws IOException {
        final String source = Files.readString(CONFIG_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("ACCESS_TOKENS_FILE"));
        assertTrue(source.contains("case ACCESS_TOKENS_FILE -> \"accessTokensFile\";"));
        assertTrue(source.contains("LoadTestTokenValues.fromCsvOrFile"));
    }

    @Test
    void defaultsHttp2ToDisabledForComparableLoadTests() throws IOException {
        final String source = Files.readString(CONFIG_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("public static boolean http2Enabled()"));
        assertTrue(source.contains("case HTTP2_ENABLED -> \"http2Enabled\";"));
        assertTrue(source.contains("case HTTP2_ENABLED -> \"false\";"));
    }

    @Test
    void defaultsPollingTimeoutToFiveMinutes() throws IOException {
        final String source = Files.readString(CONFIG_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("case POLLING_TIMEOUT_SECONDS -> \"300\";"));
    }

    @Test
    void exposesRealisticBookingTimingAndApiSpecificSlos() throws IOException {
        final String source = Files.readString(CONFIG_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("case PERFORMANCE_SUMMARY_P99_THRESHOLD_MS -> \"700\";"));
        assertTrue(source.contains("case BOOKING_SEAT_THINK_MIN_MILLIS -> \"1000\";"));
        assertTrue(source.contains("case BOOKING_ORDER_THINK_MAX_MILLIS -> \"6000\";"));
        assertTrue(source.contains("case BOOKING_SEAT_REFRESH_PERCENT -> \"25.0\";"));
        assertTrue(source.contains("case BOOKING_DROPOUT_PERCENT -> \"10.0\";"));
        assertTrue(source.contains("case SEAT_STATUS_P99_THRESHOLD_MS -> \"700\";"));
        assertTrue(source.contains("case SEAT_SELECT_P99_THRESHOLD_MS -> \"1000\";"));
        assertTrue(source.contains("case ORDER_CREATE_P99_THRESHOLD_MS -> \"1500\";"));
        assertTrue(source.contains("case ORDER_GET_P99_THRESHOLD_MS -> \"1000\";"));
    }

}
