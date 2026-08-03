package com.ticket.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingFeederTest {
    private static final String HEADER = "memberId,accessToken,seatId,admissionToken";
    private static final String DYNAMIC_HEADER = "memberId,accessToken,admissionToken";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final long PERFORMANCE_ID = 10L;

    @TempDir
    Path tempDir;

    @Test
    void acceptsOnlyExactScenarioNames() throws IOException {
        final Path file = feeder(row(1, 101, ""));

        assertEquals(1, BookingFeeder.read(file, "TICKET_OPEN_END_TO_END", 1, PERFORMANCE_ID).size());
        assertEquals(1, BookingFeeder.read(file, "QUEUE_PROTECTS_CORE", 1, PERFORMANCE_ID).size());
        assertThrows(IllegalArgumentException.class,
                () -> BookingFeeder.read(file, "ticket_open_end_to_end", 1, PERFORMANCE_ID));
        assertThrows(IllegalArgumentException.class,
                () -> BookingFeeder.read(file, "UNKNOWN", 1, PERFORMANCE_ID));
    }

    @Test
    void rejectsBomInvalidHeaderAndInvalidColumnCounts() throws IOException {
        assertInvalid("\uFEFF" + HEADER + System.lineSeparator() + row(1, 101, ""));
        assertInvalid("memberId,accessToken,seatId" + System.lineSeparator() + row(1, 101, ""));
        assertInvalid(HEADER + System.lineSeparator() + "1," + accessToken(1) + ",101");
        assertInvalid(HEADER + System.lineSeparator() + row(1, 101, "") + ",extra");
        assertInvalid(HEADER + System.lineSeparator() + "1," + accessToken(1) + ",101,broken\nvalue");
    }

    @Test
    void rejectsNonPositiveIdsAndBlankRequiredValues() throws IOException {
        assertInvalid(HEADER + System.lineSeparator() + "0," + accessToken(1) + ",101,");
        assertInvalid(HEADER + System.lineSeparator() + "1," + accessToken(1) + ",-1,");
        assertInvalid(HEADER + System.lineSeparator() + "1,,101,");
        assertInvalid(HEADER + System.lineSeparator() + row(1, 101, ""), "BOOKING_CAPACITY");
        assertInvalid(HEADER + System.lineSeparator() + row(1, 101, ""), "CORE_ADMISSION_CAPACITY");
        assertInvalid(HEADER + System.lineSeparator() + row(1, 101, ""), "HOT_SEAT_CONCURRENCY");
        assertInvalid(HEADER + System.lineSeparator() + row(1, 101, ""), "SEAT_CONTENTION");
    }

    @Test
    void enforcesMemberAndScenarioSpecificSeatUniqueness() throws IOException {
        final String duplicateMember = row(1, 101, admissionToken(1, PERFORMANCE_ID)) + System.lineSeparator()
                + row(1, 102, admissionToken(1, PERFORMANCE_ID));
        assertInvalid(HEADER + System.lineSeparator() + duplicateMember, "BOOKING_CAPACITY");

        final String duplicateSeat = row(1, 101, admissionToken(1, PERFORMANCE_ID)) + System.lineSeparator()
                + row(2, 101, admissionToken(2, PERFORMANCE_ID));
        assertInvalid(HEADER + System.lineSeparator() + duplicateSeat, "BOOKING_CAPACITY");
        assertEquals(2, BookingFeeder.read(feeder(duplicateSeat), "HOT_SEAT_CONCURRENCY", 2, PERFORMANCE_ID).size());
        assertInvalid(HEADER + System.lineSeparator() + duplicateSeat, "CORE_SPIKE");
        assertEquals(2, BookingFeeder.read(feeder(duplicateSeat), "SEAT_CONTENTION", 2, PERFORMANCE_ID).size());
    }

    @Test
    void rejectsTokenClaimMismatchesMalformedTokensAndRowShortage() throws IOException {
        assertInvalid(HEADER + System.lineSeparator() + "1," + accessToken(2) + ",101,");
        assertInvalid(HEADER + System.lineSeparator() + "1,malformed,101,");
        assertInvalid(HEADER + System.lineSeparator() + row(1, 101, admissionToken(2, PERFORMANCE_ID)),
                "BOOKING_CAPACITY");
        assertInvalid(HEADER + System.lineSeparator() + row(1, 101, admissionToken(1, 99)),
                "BOOKING_CAPACITY");
        final Path file = feeder(row(1, 101, ""));
        assertThrows(IllegalArgumentException.class,
                () -> BookingFeeder.read(file, "TICKET_OPEN_END_TO_END", 2, PERFORMANCE_ID));
    }

    @Test
    void loadPreservesFileOrderAndStopsAtEof() throws IOException {
        final Path file = feeder(row(1, 101, "") + System.lineSeparator() + row(2, 102, ""));
        final Iterator<Map<String, Object>> feeder = BookingFeeder.load(
                file, "TICKET_OPEN_END_TO_END", 2, PERFORMANCE_ID);

        assertEquals(1L, feeder.next().get("memberId"));
        assertEquals(2L, feeder.next().get("memberId"));
        assertFalse(feeder.hasNext());
    }

    @Test
    void acceptsDynamicSeatFeederOnlyForClosedAndSpikeScenarios() throws IOException {
        final String content = DYNAMIC_HEADER + System.lineSeparator()
                + dynamicRow(1, admissionToken(1, PERFORMANCE_ID));
        final Path file = writeFeeder(content);

        assertEquals(0L, BookingFeeder.read(file, "CORE_ACTIVE_USERS_CLOSED", 1, PERFORMANCE_ID)
                .getFirst().seatId());
        assertEquals(0L, BookingFeeder.read(file, "CORE_SPIKE", 1, PERFORMANCE_ID)
                .getFirst().seatId());
        assertThrows(IllegalArgumentException.class,
                () -> BookingFeeder.read(file, "BOOKING_CAPACITY", 1, PERFORMANCE_ID));
    }

    @Test
    void dynamicSeatFeederStillRequiresValidAdmissionToken() throws IOException {
        final Path missingToken = writeFeeder(DYNAMIC_HEADER + System.lineSeparator() + dynamicRow(1, ""));
        final Path mismatchedToken = writeFeeder(DYNAMIC_HEADER + System.lineSeparator()
                + dynamicRow(1, admissionToken(2, PERFORMANCE_ID)));

        assertThrows(IllegalArgumentException.class,
                () -> BookingFeeder.read(missingToken, "CORE_SPIKE", 1, PERFORMANCE_ID));
        assertThrows(IllegalArgumentException.class,
                () -> BookingFeeder.read(mismatchedToken, "CORE_SPIKE", 1, PERFORMANCE_ID));
    }

    private void assertInvalid(final String content) throws IOException {
        assertInvalid(content, "TICKET_OPEN_END_TO_END");
    }

    private void assertInvalid(final String content, final String scenario) throws IOException {
        final Path file = tempDir.resolve("invalid-" + System.nanoTime() + ".csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> BookingFeeder.read(file, scenario, 1, PERFORMANCE_ID));
    }

    private Path feeder(final String rows) throws IOException {
        return writeFeeder(HEADER + System.lineSeparator() + rows);
    }

    private Path writeFeeder(final String content) throws IOException {
        final Path file = tempDir.resolve("feeder-" + System.nanoTime() + ".csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String row(final long memberId, final long seatId, final String admissionToken) {
        return memberId + "," + accessToken(memberId) + "," + seatId + "," + admissionToken;
    }

    private static String dynamicRow(final long memberId, final String admissionToken) {
        return memberId + "," + accessToken(memberId) + "," + admissionToken;
    }

    private static String accessToken(final long memberId) {
        return LoadTestTokens.createAccessToken("ticket", SECRET, memberId, "MEMBER", Instant.EPOCH, 300);
    }

    private static String admissionToken(final long memberId, final long performanceId) {
        return LoadTestTokens.createAdmissionToken(
                "ticket-queue", "ticket-api", SECRET, memberId, performanceId, Instant.EPOCH, 300);
    }
}
