package com.ticket.loadtest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class AccessTokenFileGenerator {

    private AccessTokenFileGenerator() {
    }

    public static void main(final String[] args) {
        final Path output = Path.of(requiredProperty("output"));
        final String bookingFeederOutput = System.getProperty("bookingFeederOutput", "").trim();
        final String issuer = property("jwtIssuer", "ticket");
        final String secret = requiredProperty("jwtSecret");
        final long startMemberId = longProperty("syntheticMemberStartId", 1L);
        final int count = intProperty("tokenCount");
        final String role = property("syntheticJwtRole", "MEMBER");
        final long ttlSeconds = longProperty("syntheticTokenTtlSeconds", 3600L);
        final long bookingSeatStartId = longProperty("bookingSeatStartId", 910000001L);
        final Instant now = Instant.now();

        write(output, issuer, secret, startMemberId, count, role, ttlSeconds, now);
        if (!bookingFeederOutput.isBlank()) {
            final Path feederOutput = Path.of(bookingFeederOutput);
            writeBookingFeeder(
                    feederOutput,
                    issuer,
                    secret,
                    startMemberId,
                    bookingSeatStartId,
                    count,
                    role,
                    ttlSeconds,
                    now
            );
            System.out.println("Generated " + count + " booking feeder rows: "
                    + feederOutput.toAbsolutePath().normalize());
        }

        System.out.println("Generated " + count + " access tokens: "
                + output.toAbsolutePath().normalize());
        System.out.println("Member IDs: " + startMemberId + " ~ " + (startMemberId + count - 1));
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
        validate(secret, startMemberId, count, ttlSeconds);
        try {
            final Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                for (int offset = 0; offset < count; offset++) {
                    writer.write(LoadTestTokens.createAccessToken(
                            issuer,
                            secret,
                            startMemberId + offset,
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
        validate(secret, startMemberId, count, ttlSeconds);
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
                for (int offset = 0; offset < count; offset++) {
                    final long memberId = startMemberId + offset;
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

    private static void validate(
            final String secret,
            final long startMemberId,
            final int count,
            final long ttlSeconds
    ) {
        if (startMemberId <= 0) {
            throw new IllegalArgumentException("syntheticMemberStartId must be positive");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("tokenCount must be positive");
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
