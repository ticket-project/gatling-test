package com.ticket.gatling.console;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReportRegistry {
    private final Map<UUID, Path> reportDirectories = new ConcurrentHashMap<>();

    public void register(final UUID runId, final Path reportDirectory) {
        reportDirectories.put(runId, reportDirectory.toAbsolutePath().normalize());
    }

    public Optional<Path> resolve(final UUID runId, final String relativePath) {
        final Path reportDirectory = reportDirectories.get(runId);
        if (reportDirectory == null) {
            return Optional.empty();
        }
        final String safeRelativePath = relativePath == null || relativePath.isBlank() ? "index.html" : relativePath;
        final Path resolved = reportDirectory.resolve(safeRelativePath).normalize();
        if (!resolved.startsWith(reportDirectory)) {
            return Optional.empty();
        }
        return Optional.of(resolved);
    }
}
