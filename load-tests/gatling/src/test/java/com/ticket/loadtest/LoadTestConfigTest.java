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
    void exposesBookingFeederAndResultProperties() throws IOException {
        final String source = Files.readString(CONFIG_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("public static String bookingFeederFile()"));
        assertTrue(source.contains("public static String bookingScenario()"));
        assertTrue(source.contains("public static int nodeIndex()"));
        assertTrue(source.contains("public static String resultFile()"));
        assertTrue(source.contains("public static int pollingTimeoutSeconds()"));
        assertTrue(source.contains("public static Iterator<Map<String, Object>> bookingFeeder()"));
        assertTrue(source.contains("BookingFeeder.load"));
    }
}
