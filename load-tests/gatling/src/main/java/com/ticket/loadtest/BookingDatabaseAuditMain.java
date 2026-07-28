package com.ticket.loadtest;

import java.nio.file.Path;

public final class BookingDatabaseAuditMain {
    private BookingDatabaseAuditMain() {
    }

    public static void main(final String[] args) {
        final Path resultFile = Path.of(requiredProperty("resultFile"));
        final long performanceId = Long.parseLong(requiredProperty("performanceId"));
        final String configuredOutput = System.getProperty("dbAuditOutput");
        final Path output = configuredOutput == null || configuredOutput.isBlank()
                ? resultFile.toAbsolutePath().resolveSibling("booking-db-audit.json")
                : Path.of(configuredOutput);
        BookingDatabaseAuditor.audit(resultFile, performanceId, output);
        System.out.println("Booking DB audit: " + output.toAbsolutePath().normalize());
    }

    private static String requiredProperty(final String name) {
        final String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: -D" + name);
        }
        return value.trim();
    }
}