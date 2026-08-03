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
    private static final String FIXED_SEAT_HEADER = "memberId,accessToken,seatId,admissionToken";
    private static final String DYNAMIC_SEAT_HEADER = "memberId,accessToken,admissionToken";

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
        final BookingScenario bookingScenario = BookingScenario.parse(scenario);
        final List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read booking feeder file: " + file, exception);
        }
        if (lines.isEmpty() || lines.getFirst().startsWith("\uFEFF")) {
            throw new IllegalArgumentException("Booking feeder must be BOM-free UTF-8 CSV");
        }
        final boolean dynamicSeatHeader = DYNAMIC_SEAT_HEADER.equals(lines.getFirst());
        final boolean validHeader = FIXED_SEAT_HEADER.equals(lines.getFirst())
                || (dynamicSeatHeader && bookingScenario.dynamicSeatSelection());
        if (!validHeader) {
            throw new IllegalArgumentException("Booking feeder header must be " + FIXED_SEAT_HEADER
                    + (bookingScenario.dynamicSeatSelection() ? " or " + DYNAMIC_SEAT_HEADER : ""));
        }

        final boolean admissionRequired = bookingScenario.admissionRequired();
        final boolean uniqueSeats = bookingScenario.uniqueSeats();
        final Set<Long> memberIds = new HashSet<>();
        final Set<Long> seatIds = new HashSet<>();
        final List<BookingRow> rows = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            final String[] columns = lines.get(index).split(",", -1);
            final int expectedColumns = dynamicSeatHeader ? 3 : 4;
            if (columns.length != expectedColumns) {
                throw invalidRow(index, "exactly " + expectedColumns + " columns are required");
            }
            final long memberId = positiveLong(columns[0], index, "memberId");
            final String accessToken = columns[1].trim();
            final long seatId = dynamicSeatHeader ? 0L : positiveLong(columns[2], index, "seatId");
            final String admissionToken = columns[dynamicSeatHeader ? 2 : 3].trim();
            if (accessToken.isEmpty()) {
                throw invalidRow(index, "accessToken is required");
            }
            if (admissionRequired && admissionToken.isEmpty()) {
                throw invalidRow(index, "admissionToken is required");
            }
            if (!memberIds.add(memberId)) {
                throw invalidRow(index, "memberId must be unique");
            }
            if (!dynamicSeatHeader && uniqueSeats && !seatIds.add(seatId)) {
                throw invalidRow(index, "seatId must be unique for scenario " + bookingScenario);
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

    private enum BookingScenario {
        BOOKING_CAPACITY(true, true),
        TICKET_OPEN_END_TO_END(false, true),
        SEAT_CONTENTION(true, false),
        SMOKE(true, true),
        HOT_SEAT_CONCURRENCY(true, false),
        CORE_ADMISSION_CAPACITY(true, true),
        CORE_ACTIVE_USERS_CLOSED(true, true),
        CORE_SPIKE(true, true),
        QUEUE_PROTECTS_CORE(false, true);

        private final boolean admissionRequired;
        private final boolean uniqueSeats;

        BookingScenario(final boolean admissionRequired, final boolean uniqueSeats) {
            this.admissionRequired = admissionRequired;
            this.uniqueSeats = uniqueSeats;
        }

        private boolean admissionRequired() {
            return admissionRequired;
        }

        private boolean uniqueSeats() {
            return uniqueSeats;
        }

        private boolean dynamicSeatSelection() {
            return this == CORE_ACTIVE_USERS_CLOSED
                    || this == CORE_SPIKE;
        }

        private static BookingScenario parse(final String value) {
            try {
                return BookingScenario.valueOf(value);
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new IllegalArgumentException("Unsupported booking scenario: " + value, exception);
            }
        }
    }
}
