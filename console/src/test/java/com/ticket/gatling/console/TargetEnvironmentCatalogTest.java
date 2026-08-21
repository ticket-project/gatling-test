package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetEnvironmentCatalogTest {
    @TempDir
    Path tempDir;

    @Test
    void readsTargetsInDeclaredOrder() throws IOException {
        final Path file = tempDir.resolve("environments.properties");
        Files.writeString(file, """
                targets=local,prod

                local.label=로컬 (H2)
                local.coreBaseUrl=http://localhost:8080
                local.queueBaseUrl=http://localhost:8081

                prod.label=운영 (oneticket.site)
                prod.coreBaseUrl=https://oneticket.site
                prod.queueBaseUrl=https://queue.oneticket.site
                """, StandardCharsets.UTF_8);

        final List<TargetEnvironment> targets = TargetEnvironmentCatalog.load(file);

        assertEquals(2, targets.size());
        assertEquals("local", targets.get(0).key());
        assertEquals("로컬 (H2)", targets.get(0).label());
        assertEquals("http://localhost:8080", targets.get(0).coreBaseUrl());
        assertEquals("http://localhost:8081", targets.get(0).queueBaseUrl());
        assertEquals("prod", targets.get(1).key());
        assertEquals("https://oneticket.site", targets.get(1).coreBaseUrl());
    }

    @Test
    void returnsEmptyListWhenFileIsMissing() {
        final List<TargetEnvironment> targets =
                TargetEnvironmentCatalog.load(tempDir.resolve("absent.properties"));

        assertTrue(targets.isEmpty());
    }

    @Test
    void skipsTargetsWithoutCoreBaseUrl() throws IOException {
        final Path file = tempDir.resolve("environments.properties");
        Files.writeString(file, """
                targets=broken,prod
                broken.label=설정이 빠진 대상
                prod.label=운영
                prod.coreBaseUrl=https://oneticket.site
                """, StandardCharsets.UTF_8);

        final List<TargetEnvironment> targets = TargetEnvironmentCatalog.load(file);

        assertEquals(1, targets.size());
        assertEquals("prod", targets.get(0).key());
        assertEquals("", targets.get(0).queueBaseUrl());
    }

    @Test
    void fallsBackToKeyWhenLabelIsMissing() throws IOException {
        final Path file = tempDir.resolve("environments.properties");
        Files.writeString(file, """
                targets=staging
                staging.coreBaseUrl=https://staging.example.com
                """, StandardCharsets.UTF_8);

        final List<TargetEnvironment> targets = TargetEnvironmentCatalog.load(file);

        assertEquals("staging", targets.get(0).label());
    }
}
