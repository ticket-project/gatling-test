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
}
