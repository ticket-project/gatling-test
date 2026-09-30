package com.ticket.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                List.of(100L, 101L, 102L),
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
                List.of(1L, 2L),
                910000001L,
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

    @Test
    void readsNonConsecutiveExistingMemberIdsFromOracleExport() throws Exception {
        Path memberIds = tempDir.resolve("member-ids.txt");
        Files.writeString(memberIds, "MEMBER_ID\n1\n5\n11\n");

        List<Long> loaded = AccessTokenFileGenerator.readMemberIds(memberIds, 3);

        assertEquals(List.of(1L, 5L, 11L), loaded);
    }

    @Test
    void rejectsMemberIdFileShortageBeforeTokenGeneration() throws Exception {
        Path memberIds = tempDir.resolve("member-ids.txt");
        Files.writeString(memberIds, "memberId\n1\n5\n");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> AccessTokenFileGenerator.readMemberIds(memberIds, 3)
        );

        assertEquals("Member ID file has fewer IDs than required: required=3, actual=2", failure.getMessage());
    }
}
