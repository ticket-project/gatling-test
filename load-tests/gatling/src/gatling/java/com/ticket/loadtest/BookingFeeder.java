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

public final class BookingFeeder {
    private static final String HEADER = "memberId,accessToken,seatId,admissionToken";

    private BookingFeeder() {
    }

    public static Iterator<Map<String, Object>> load(
            final Path file,
            final String scenario,
            final int expectedRows,
            final long performanceId
    ) {
        final List<BookingRow> rows = read(file, scenario, expectedRows, performanceId);
        return rows.stream().map(BookingFeeder::toMap).iterator();
    }

    static List<BookingRow> read(final Path file, final String scenario, final int expectedRows, final long performanceId) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read booking feeder file: " + file, exception);
        }
        if (lines.isEmpty() || lines.getFirst().startsWith("\uFEFF") || !HEADER.equals(lines.getFirst())) {
            throw new IllegalArgumentException("Booking feeder must be BOM-free UTF-8 CSV with header: " + HEADER);
        }

        final boolean admissionRequired = "capacity".equals(scenario) || "contention".equals(scenario);
        final boolean uniqueSeats = "capacity".equals(scenario);
        final Set<Long> memberIds = new HashSet<>();
        final Set<Long> seatIds = new HashSet<>();
        final List<BookingRow> rows = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            final String[] columns = lines.get(index).split(",", -1);
            if (columns.length != 4) {
                throw invalidRow(index, "exactly 4 columns are required");
            }
            final long memberId = positiveLong(columns[0], index, "memberId");
            final String accessToken = columns[1].trim();
            final long seatId = positiveLong(columns[2], index, "seatId");
            final String admissionToken = columns[3].trim();
            if (accessToken.isEmpty()) {
                throw invalidRow(index, "accessToken is required");
            }
            if (admissionRequired && admissionToken.isEmpty()) {
                throw invalidRow(index, "admissionToken is required");
            }
            if (!memberIds.add(memberId)) {
                throw invalidRow(index, "memberId must be unique");
            }
            if (uniqueSeats && !seatIds.add(seatId)) {
                throw invalidRow(index, "seatId must be unique for capacity scenario");
            }
            try {
                if (LoadTestTokens.readSubjectAsLong(accessToken) != memberId) {
                    throw invalidRow(index, "accessToken subject does not match memberId");
                }
                if (!admissionToken.isEmpty()
                        && (LoadTestTokens.readSubjectAsLong(admissionToken) != memberId
                        || LoadTestTokens.readPerformanceIdAsLong(admissionToken) != performanceId)) {
                    throw invalidRow(index, "admissionToken claims do not match feeder row");
                }
            } catch (IllegalArgumentException exception) {
                throw invalidRow(index, "token claims are invalid");
            }
            rows.add(new BookingRow(memberId, accessToken, seatId, admissionToken));
        }
        if (rows.size() < expectedRows) {
            throw new IllegalArgumentException("Booking feeder has fewer rows than expected: expected="
                    + expectedRows + ", actual=" + rows.size());
        }
        return List.copyOf(rows);
    }

    private static long positiveLong(final String value, final int index, final String name) {
        try {
            final long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw invalidRow(index, name + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalidRow(index, name + " must be a positive integer");
        }
    }

    private static IllegalArgumentException invalidRow(final int zeroBasedIndex, final String message) {
        return new IllegalArgumentException("Invalid booking feeder row " + (zeroBasedIndex + 1) + ": " + message);
    }

    private static Map<String, Object> toMap(final BookingRow row) {
        return Map.of("memberId", row.memberId(), "accessToken", row.accessToken(), "seatId", row.seatId(),
                "admissionToken", row.admissionToken());
    }

    public record BookingRow(long memberId, String accessToken, long seatId, String admissionToken) {
    }
}
