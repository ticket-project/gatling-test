package com.ticket.loadtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OrderLookupFeeder {
    private static final String HEADER = "memberId,accessToken,orderKey";

    private OrderLookupFeeder() {
    }

    public static Iterator<Map<String, Object>> load(final Path file, final int expectedRows) {
        return read(file, expectedRows).stream()
                .map(row -> Map.<String, Object>of(
                        "memberId", row.memberId(),
                        "accessToken", row.accessToken(),
                        "orderKey", row.orderKey()
                ))
                .iterator();
    }

    static List<OrderLookupRow> read(final Path file, final int expectedRows) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read order lookup feeder file: " + file, exception);
        }
        if (lines.isEmpty() || lines.getFirst().startsWith("\uFEFF")) {
            throw new IllegalArgumentException("Order lookup feeder must be BOM-free UTF-8 CSV");
        }
        if (!HEADER.equals(lines.getFirst())) {
            throw new IllegalArgumentException("Order lookup feeder header must be " + HEADER);
        }

        final Set<Long> memberIds = new HashSet<>();
        final Set<String> orderKeys = new HashSet<>();
        final List<OrderLookupRow> rows = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            final String[] columns = lines.get(index).split(",", -1);
            if (columns.length != 3) {
                throw invalidRow(index, "exactly 3 columns are required");
            }
            final long memberId = positiveLong(columns[0], index);
            final String accessToken = columns[1].trim();
            final String orderKey = columns[2].trim();
            if (accessToken.isEmpty()) {
                throw invalidRow(index, "accessToken is required");
            }
            if (orderKey.isEmpty()) {
                throw invalidRow(index, "orderKey is required");
            }
            if (!memberIds.add(memberId)) {
                throw invalidRow(index, "memberId must be unique");
            }
            if (!orderKeys.add(orderKey)) {
                throw invalidRow(index, "orderKey must be unique");
            }
            try {
                if (LoadTestTokens.readSubjectAsLong(accessToken) != memberId) {
                    throw invalidRow(index, "accessToken subject does not match memberId");
                }
            } catch (IllegalArgumentException exception) {
                throw invalidRow(index, "accessToken claims are invalid");
            }
            rows.add(new OrderLookupRow(memberId, accessToken, orderKey));
        }
        if (rows.size() < expectedRows) {
            throw new IllegalArgumentException("Order lookup feeder has fewer rows than expected: expected="
                    + expectedRows + ", actual=" + rows.size());
        }
        return List.copyOf(rows);
    }

    private static long positiveLong(final String value, final int index) {
        try {
            final long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw invalidRow(index, "memberId must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalidRow(index, "memberId must be a positive integer");
        }
    }

    private static IllegalArgumentException invalidRow(final int zeroBasedIndex, final String message) {
        return new IllegalArgumentException("Invalid order lookup feeder row "
                + (zeroBasedIndex + 1) + ": " + message);
    }

    record OrderLookupRow(long memberId, String accessToken, String orderKey) {
    }
}
