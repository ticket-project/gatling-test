package com.ticket.gatling.console;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public class LocalFolderOpener {
    private final String osName;

    public LocalFolderOpener() {
        this(System.getProperty("os.name"));
    }

    LocalFolderOpener(final String osName) {
        this.osName = osName == null ? "" : osName;
    }

    public void open(final Path directory) throws IOException {
        final Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Report folder not found: " + normalized);
        }
        new ProcessBuilder(openCommand(normalized)).start();
    }

    List<String> openCommand(final Path directory) {
        final String normalizedOsName = osName.toLowerCase(Locale.ROOT);
        final String path = directory.toAbsolutePath().normalize().toString();
        if (normalizedOsName.contains("win")) {
            return List.of("explorer.exe", path);
        }
        if (normalizedOsName.contains("mac")) {
            return List.of("open", path);
        }
        return List.of("xdg-open", path);
    }
}
