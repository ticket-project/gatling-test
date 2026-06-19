package com.ticket.loadtest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdnCacheCountersTest {

    @Test
    void classifiesCommonCdnCacheHeaders() {
        final CdnCacheCounters counters = new CdnCacheCounters();

        counters.record("Hit from cloudfront", null, null, null);
        counters.record(null, "HIT", null, null);
        counters.record(null, null, "cache.example; hit", null);
        counters.record("Miss from cloudfront", null, null, null);
        counters.record(null, "BYPASS", null, null);
        counters.record(null, null, null, null);

        assertEquals(3, counters.hits());
        assertEquals(2, counters.misses());
        assertEquals(1, counters.unknowns());
        assertEquals(6, counters.total());
    }

    @Test
    void rendersSummaryWithHitRatio() {
        final CdnCacheCounters counters = new CdnCacheCounters();

        counters.record("HIT", null, null, null);
        counters.record("MISS", null, null, null);

        final String summary = counters.summary();

        assertTrue(summary.contains("hit=1"));
        assertTrue(summary.contains("miss=1"));
        assertTrue(summary.contains("unknown=0"));
        assertTrue(summary.contains("hitRatio=50.00%"));
    }

    @Test
    void writesSummaryToFile() throws IOException {
        final CdnCacheCounters counters = new CdnCacheCounters();
        final Path summaryPath = Files.createTempDirectory("cdn-cache-counters")
                .resolve("nested")
                .resolve("cdn-cache-summary.txt");

        counters.record("HIT", null, null, null);

        assertTrue(counters.writeSummary(summaryPath));
        assertEquals(
                counters.summary() + System.lineSeparator(),
                Files.readString(summaryPath, StandardCharsets.UTF_8)
        );
    }

    @Test
    void summaryPathTargetsLatestMatchingReportDirectory() throws IOException {
        final Path reportsRoot = Files.createTempDirectory("gatling-reports");
        final Path olderReport = Files.createDirectories(reportsRoot.resolve("cdnpublicstatesimulation-older"));
        final Path newerReport = Files.createDirectories(reportsRoot.resolve("cdnpublicstatesimulation-newer"));
        Files.createDirectories(reportsRoot.resolve("other-simulation-newer"));

        Files.setLastModifiedTime(olderReport, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(newerReport, FileTime.fromMillis(2_000));

        assertEquals(
                newerReport.resolve("cdn-cache-summary.txt"),
                CdnCacheCounters.summaryPathForLatestReport(reportsRoot, "cdnpublicstatesimulation-")
        );
    }

    @Test
    void defaultReportsRootHonorsGatlingReportDirSystemProperty() throws IOException {
        final String previousValue = System.getProperty("gatlingReportDir");
        final Path reportsRoot = Files.createTempDirectory("gatling-report-root");
        try {
            System.setProperty("gatlingReportDir", reportsRoot.toString());

            assertEquals(
                    reportsRoot.toAbsolutePath().normalize(),
                    CdnCacheCounters.defaultReportsRoot()
            );
        } finally {
            if (previousValue == null) {
                System.clearProperty("gatlingReportDir");
            } else {
                System.setProperty("gatlingReportDir", previousValue);
            }
        }
    }
}
