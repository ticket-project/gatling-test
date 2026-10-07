package com.ticket.loadtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class OrderLookupFeeder {
    private static final String HEADER = "memberId,orderKey";
    private static final Object WRITE_LOCK = new Object();

    private OrderLookupFeeder() {
    }

    public static void initialize(final Path file) {
        synchronized (WRITE_LOCK) {
            try {
                final Path parent = file.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        file,
                        HEADER + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to initialize order lookup feeder: " + file, exception);
            }
        }
    }

    public static void append(
            final Path file,
            final long memberId,
            final String orderKey
    ) {
        if (orderKey == null || orderKey.isBlank() || orderKey.contains(",")) {
            throw new IllegalArgumentException("orderKey must be a non-empty CSV-safe value");
        }
        final String line = memberId + "," + orderKey + System.lineSeparator();
        synchronized (WRITE_LOCK) {
            try {
                Files.writeString(
                        file,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to append order lookup feeder: " + file, exception);
            }
        }
    }
}
