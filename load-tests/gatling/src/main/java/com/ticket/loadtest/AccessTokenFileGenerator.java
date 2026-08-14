package com.ticket.loadtest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AccessTokenFileGenerator {

    private AccessTokenFileGenerator() {
    }

    public static void main(final String[] args) {
        final Path output = Path.of(requiredProperty("output"));
        final String bookingFeederOutput = System.getProperty("bookingFeederOutput", "").trim();
        final String memberIdsFile = System.getProperty("memberIdsFile", "").trim();
        final String issuer = property("jwtIssuer", "ticket");
        final String secret = requiredProperty("jwtSecret");
        final long startMemberId = longProperty("syntheticMemberStartId", 1L);
        final int count = intProperty("tokenCount");
        final String role = property("syntheticJwtRole", "MEMBER");
        final long ttlSeconds = longProperty("syntheticTokenTtlSeconds", 3600L);
        final long bookingSeatStartId = longProperty("bookingSeatStartId", 910000001L);
        final Instant now = Instant.now();
        final List<Long> memberIds = memberIdsFile.isBlank()
                ? consecutiveMemberIds(startMemberId, count)
                : readMemberIds(Path.of(memberIdsFile), count);

        write(output, issuer, secret, memberIds, role, ttlSeconds, now);
        if (!bookingFeederOutput.isBlank()) {
            final Path feederOutput = Path.of(bookingFeederOutput);
            writeBookingFeeder(
                    feederOutput,
                    issuer,
                    secret,
                    memberIds,
                    bookingSeatStartId,
                    role,
                    ttlSeconds,
                    now
            );
            System.out.println("Generated " + count + " booking feeder rows: "
                    + feederOutput.toAbsolutePath().normalize());
        }

        System.out.println("Generated " + count + " access tokens: "
                + output.toAbsolutePath().normalize());
        System.out.println("Member IDs: " + memberIds.getFirst() + " ... " + memberIds.getLast());
    }

    public static void write(
            final Path output,
            final String issuer,
            final String secret,
            final long startMemberId,
            final int count,
            final String role,
            final long ttlSeconds,
            final Instant now
    ) {
        write(output, issuer, secret, consecutiveMemberIds(startMemberId, count), role, ttlSeconds, now);
    }

    static void write(
            final Path output,
            final String issuer,
            final String secret,
            final List<Long> memberIds,
            final String role,
            final long ttlSeconds,
            final Instant now
    ) {
        validate(secret, memberIds, ttlSeconds);
        try {
            final Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                for (long memberId : memberIds) {
                    writer.write(LoadTestTokens.createAccessToken(
                            issuer,
                            secret,
                            memberId,
                            role,
                            now,
                            ttlSeconds
                    ));
                    writer.newLine();
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write access token file: " + output, exception);
        }
    }

    public static void writeBookingFeeder(
            final Path output,
            final String issuer,
            final String secret,
            final long startMemberId,
            final long startSeatId,
            final int count,
            final String role,
            final long ttlSeconds,
            final Instant now
    ) {
        writeBookingFeeder(
                output,
                issuer,
                secret,
                consecutiveMemberIds(startMemberId, count),
                startSeatId,
                role,
                ttlSeconds,
                now
        );
    }

    static void writeBookingFeeder(
            final Path output,
            final String issuer,
            final String secret,
            final List<Long> memberIds,
            final long startSeatId,
            final String role,
            final long ttlSeconds,
            final Instant now
    ) {
        validate(secret, memberIds, ttlSeconds);
        if (startSeatId <= 0) {
            throw new IllegalArgumentException("bookingSeatStartId must be positive");
        }
        try {
            final Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                writer.write("memberId,accessToken,seatId,admissionToken");
                writer.newLine();
                for (int offset = 0; offset < memberIds.size(); offset++) {
                    final long memberId = memberIds.get(offset);
                    final String accessToken = LoadTestTokens.createAccessToken(
                            issuer,
                            secret,
                            memberId,
                            role,
                            now,
                            ttlSeconds
                    );
                    writer.write(memberId + "," + accessToken + "," + (startSeatId + offset) + ",");
                    writer.newLine();
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write booking feeder file: " + output, exception);
        }
    }

    static List<Long> readMemberIds(final Path file, final int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("tokenCount must be positive");
        }
        final String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Member ID file cannot be read: " + file, exception);
        }
        final List<Long> memberIds = new ArrayList<>(count);
        final Set<Long> uniqueMemberIds = new HashSet<>();
        for (String raw : content.replace("\uFEFF", "").split("[,\\r\\n]+")) {
            final String value = raw.trim().replace("\"", "");
            if (value.isBlank() || value.equalsIgnoreCase("memberId") || value.equalsIgnoreCase("member_id")) {
                continue;
            }
            final long memberId;
            try {
                memberId = Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Member ID file contains a non-numeric value: " + value, exception);
            }
            if (memberId <= 0) {
                throw new IllegalArgumentException("Member ID file contains a non-positive ID: " + memberId);
            }
            if (!uniqueMemberIds.add(memberId)) {
                throw new IllegalArgumentException("Member ID file contains a duplicate ID: " + memberId);
            }
            memberIds.add(memberId);
            if (memberIds.size() == count) {
                return List.copyOf(memberIds);
            }
        }
        throw new IllegalArgumentException("Member ID file has fewer IDs than required: required="
                + count + ", actual=" + memberIds.size());
    }

    private static List<Long> consecutiveMemberIds(final long startMemberId, final int count) {
        if (startMemberId <= 0) {
            throw new IllegalArgumentException("syntheticMemberStartId must be positive");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("tokenCount must be positive");
        }
        final List<Long> memberIds = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            memberIds.add(Math.addExact(startMemberId, offset));
        }
        return List.copyOf(memberIds);
    }

    private static void validate(
            final String secret,
            final List<Long> memberIds,
            final long ttlSeconds
    ) {
        if (memberIds.isEmpty()) {
            throw new IllegalArgumentException("memberIds must not be empty");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("syntheticTokenTtlSeconds must be positive");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("jwtSecret must be at least 32 bytes");
        }
    }

    private static String requiredProperty(final String name) {
        final String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: -D" + name);
        }
        return value.trim();
    }

    private static String property(final String name, final String defaultValue) {
        final String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static int intProperty(final String name) {
        return Integer.parseInt(requiredProperty(name));
    }

    private static long longProperty(final String name, final long defaultValue) {
        return Long.parseLong(property(name, String.valueOf(defaultValue)));
    }
}
