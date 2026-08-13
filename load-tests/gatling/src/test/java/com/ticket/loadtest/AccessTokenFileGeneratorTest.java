package com.ticket.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessTokenFileGeneratorTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-06-26T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void writesOneAccessTokenPerMemberId() throws Exception {
        Path output = tempDir.resolve("tokens").resolve("access-tokens.txt");

        AccessTokenFileGenerator.write(
                output,
                "ticket",
                SECRET,
                100L,
                3,
                "MEMBER",
                3600,
                NOW
        );

        List<String> tokens = Files.readAllLines(output);

        assertEquals(3, tokens.size());
        assertEquals(100L, LoadTestTokens.readSubjectAsLong(tokens.get(0)));
        assertEquals(101L, LoadTestTokens.readSubjectAsLong(tokens.get(1)));
        assertEquals(102L, LoadTestTokens.readSubjectAsLong(tokens.get(2)));
    }

    @Test
    void writesBookingFeederWithMatchingMembersTokensAndSeats() throws Exception {
        Path output = tempDir.resolve("feeders").resolve("booking-feeder.csv");

        AccessTokenFileGenerator.writeBookingFeeder(
                output,
                "ticket",
                SECRET,
                1L,
                910000001L,
                2,
                "MEMBER",
                3600,
                NOW
        );

        List<String> lines = Files.readAllLines(output);
        String[] first = lines.get(1).split(",", -1);
        String[] second = lines.get(2).split(",", -1);

        assertEquals("memberId,accessToken,seatId,admissionToken", lines.get(0));
        assertEquals("1", first[0]);
        assertEquals(1L, LoadTestTokens.readSubjectAsLong(first[1]));
        assertEquals("910000001", first[2]);
        assertEquals("", first[3]);
        assertEquals("2", second[0]);
        assertEquals(2L, LoadTestTokens.readSubjectAsLong(second[1]));
        assertEquals("910000002", second[2]);
    }
}
