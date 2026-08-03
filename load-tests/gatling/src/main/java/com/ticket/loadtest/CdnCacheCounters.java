package com.ticket.loadtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

public final class CdnCacheCounters {

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder unknowns = new LongAdder();

    public void record(
            final String xCache,
            final String cfCacheStatus,
            final String cacheStatus,
            final String age
    ) {
        switch (classify(xCache, cfCacheStatus, cacheStatus, age)) {
            case HIT -> hits.increment();
            case MISS -> misses.increment();
            case UNKNOWN -> unknowns.increment();
        }
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    public long unknowns() {
        return unknowns.sum();
    }

    public long total() {
        return hits() + misses() + unknowns();
    }

    public String summary() {
        final long total = total();
        final double hitRatio = total == 0 ? 0.0 : (hits() * 100.0 / total);
        return String.format(
                Locale.ROOT,
                "CDN cache summary: total=%d, hit=%d, miss=%d, unknown=%d, hitRatio=%.2f%%",
                total,
                hits(),
                misses(),
                unknowns(),
                hitRatio
        );
    }

    public void printSummary() {
        System.out.println(summary());
    }

    public boolean writeSummary(final Path summaryPath) {
        try {
            final Path parent = summaryPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(summaryPath, summary() + System.lineSeparator(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            System.err.println("Failed to write CDN cache summary: " + summaryPath + " (" + exception.getMessage() + ")");
            return false;
        }
    }

    public static Path defaultSummaryPath(final String reportDirectoryPrefix) {
        return summaryPathForLatestReport(defaultReportsRoot(), reportDirectoryPrefix);
    }

    public static Path defaultReportsRoot() {
        final String configuredReportsRoot = System.getProperty("gatlingReportDir");
        if (configuredReportsRoot != null && !configuredReportsRoot.isBlank()) {
            return Path.of(configuredReportsRoot).toAbsolutePath().normalize();
        }
        return Path.of("..", "..", "distributed-results-join").toAbsolutePath().normalize();
    }

    public static Path summaryPathForLatestReport(
            final Path reportsRoot,
            final String reportDirectoryPrefix
    ) {
        return latestReportDirectory(reportsRoot, reportDirectoryPrefix)
                .orElse(reportsRoot)
                .resolve("cdn-cache-summary.txt");
    }

    private static Optional<Path> latestReportDirectory(
            final Path reportsRoot,
            final String reportDirectoryPrefix
    ) {
        if (!Files.isDirectory(reportsRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(reportsRoot)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(reportDirectoryPrefix))
                    .max(Comparator.comparingLong(CdnCacheCounters::lastModified));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static long lastModified(final Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private CacheOutcome classify(
            final String xCache,
            final String cfCacheStatus,
            final String cacheStatus,
            final String age
    ) {
        final String combined = String.join(
                " ",
                normalize(xCache),
                normalize(cfCacheStatus),
                normalize(cacheStatus)
        );
        if (combined.contains("hit")) {
            return CacheOutcome.HIT;
        }
        if (combined.contains("miss")
                || combined.contains("bypass")
                || combined.contains("dynamic")
                || combined.contains("expired")
                || combined.contains("revalidated")
                || combined.contains("updating")
                || combined.contains("stale")
                || combined.contains("pass")) {
            return CacheOutcome.MISS;
        }
        if (hasPositiveAge(age)) {
            return CacheOutcome.HIT;
        }
        return CacheOutcome.UNKNOWN;
    }

    private static String normalize(final String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean hasPositiveAge(final String age) {
        if (age == null || age.isBlank()) {
            return false;
        }
        try {
            return Long.parseLong(age.trim()) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private enum CacheOutcome {
        HIT,
        MISS,
        UNKNOWN
    }
}
