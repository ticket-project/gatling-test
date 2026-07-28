package com.ticket.loadtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BookingDatabaseAuditor {
    static final String DB_URL_ENV = "BOOKING_AUDIT_DB_URL";
    static final String DB_USERNAME_ENV = "BOOKING_AUDIT_DB_USERNAME";
    static final String DB_PASSWORD_ENV = "BOOKING_AUDIT_DB_PASSWORD";
    static final String DB_DRIVER_ENV = "BOOKING_AUDIT_DB_DRIVER";
    private static final String DEFAULT_DRIVER = "oracle.jdbc.OracleDriver";

    private BookingDatabaseAuditor() {
    }

    public static AuditResult audit(
            final Path resultFile,
            final long performanceId,
            final Path outputFile
    ) {
        final DatabaseCredentials credentials = DatabaseCredentials.fromEnvironment(System.getenv());
        try {
            Class.forName(credentials.driverClassName());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Booking DB audit JDBC driver is not available: "
                    + credentials.driverClassName(), exception);
        }

        final long clientSuccessOrders = countClientSuccesses(resultFile);
        try (Connection connection = DriverManager.getConnection(
                credentials.url(), credentials.username(), credentials.password())) {
            connection.setReadOnly(true);
            final long databaseOrders = scalar(connection,
                    "SELECT COUNT(*) FROM ORDERS WHERE performance_id = ?", performanceId);
            final long duplicateOrderSeats = scalar(connection, """
                    SELECT COUNT(*) FROM (
                        SELECT os.seat_id
                        FROM ORDER_SEATS os
                        JOIN ORDERS o ON o.id = os.order_id
                        WHERE o.performance_id = ?
                        GROUP BY os.seat_id
                        HAVING COUNT(*) > 1
                    )
                    """, performanceId);
            final long activeDuplicateHolds = scalar(connection, """
                    SELECT COUNT(*) FROM (
                        SELECT os.seat_id
                        FROM ORDER_SEATS os
                        JOIN ORDERS o ON o.id = os.order_id
                        WHERE o.performance_id = ?
                          AND o.status = 'PENDING'
                          AND o.expires_at > CURRENT_TIMESTAMP
                        GROUP BY os.seat_id
                        HAVING COUNT(*) > 1
                    )
                    """, performanceId);
            final long ordersWithoutSeats = scalar(connection, """
                    SELECT COUNT(*)
                    FROM ORDERS o
                    WHERE o.performance_id = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM ORDER_SEATS os WHERE os.order_id = o.id
                      )
                    """, performanceId);
            final long ordersWithoutCreatedHoldHistory = scalar(connection, """
                    SELECT COUNT(*)
                    FROM ORDERS o
                    WHERE o.performance_id = ?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM HOLD_HISTORY h
                          WHERE h.hold_key = o.hold_key
                            AND h.event_type = 'CREATED'
                      )
                    """, performanceId);
            final long duplicatePerformanceSeats = scalar(connection, """
                    SELECT COUNT(*) FROM (
                        SELECT seat_id
                        FROM PERFORMANCE_SEATS
                        WHERE performance_id = ?
                        GROUP BY seat_id
                        HAVING COUNT(*) > 1
                    )
                    """, performanceId);

            final AuditResult result = new AuditResult(
                    performanceId,
                    clientSuccessOrders,
                    databaseOrders,
                    duplicateOrderSeats,
                    activeDuplicateHolds,
                    ordersWithoutSeats,
                    ordersWithoutCreatedHoldHistory,
                    duplicatePerformanceSeats
            );
            writeResult(outputFile, result);
            if (!result.passed()) {
                throw new IllegalStateException("Booking DB consistency audit failed: " + result.failureSummary());
            }
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("Booking DB consistency audit could not query the database", exception);
        }
    }

    private static long scalar(final Connection connection, final String sql, final long performanceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, performanceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("DB audit query returned no row");
                }
                return resultSet.getLong(1);
            }
        }
    }

    private static long countClientSuccesses(final Path resultFile) {
        if (!Files.isRegularFile(resultFile)) {
            throw new IllegalStateException("Booking result file not found for DB audit: " + resultFile);
        }
        try {
            final List<String> lines = Files.readAllLines(resultFile, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                throw new IllegalStateException("Booking result file is empty: " + resultFile);
            }
            final List<String> headers = parseCsvLine(lines.getFirst());
            final int resultIndex = headers.indexOf("result");
            if (resultIndex < 0) {
                throw new IllegalStateException("Booking result CSV has no result column: " + resultFile);
            }
            long successes = 0;
            for (int index = 1; index < lines.size(); index++) {
                if (lines.get(index).isBlank()) {
                    continue;
                }
                final List<String> values = parseCsvLine(lines.get(index));
                if (resultIndex < values.size() && "SUCCESS".equals(values.get(resultIndex))) {
                    successes++;
                }
            }
            return successes;
        } catch (IOException exception) {
            throw new IllegalStateException("Booking result CSV could not be read: " + resultFile, exception);
        }
    }

    static List<String> parseCsvLine(final String line) {
        final List<String> values = new ArrayList<>();
        final StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            final char current = line.charAt(index);
            if (quoted && current == '"' && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                value.append('"');
                index++;
            } else if (current == '"') {
                quoted = !quoted;
            } else if (current == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        values.add(value.toString());
        return values;
    }

    private static void writeResult(final Path outputFile, final AuditResult result) {
        final String json = "{\n"
                + "  \"performanceId\":" + result.performanceId() + ",\n"
                + "  \"clientSuccessOrders\":" + result.clientSuccessOrders() + ",\n"
                + "  \"databaseOrders\":" + result.databaseOrders() + ",\n"
                + "  \"duplicateOrderSeats\":" + result.duplicateOrderSeats() + ",\n"
                + "  \"activeDuplicateHolds\":" + result.activeDuplicateHolds() + ",\n"
                + "  \"ordersWithoutSeats\":" + result.ordersWithoutSeats() + ",\n"
                + "  \"ordersWithoutCreatedHoldHistory\":"
                + result.ordersWithoutCreatedHoldHistory() + ",\n"
                + "  \"duplicatePerformanceSeats\":" + result.duplicatePerformanceSeats() + ",\n"
                + "  \"passed\":" + result.passed() + "\n"
                + "}\n";
        try {
            final Path parent = outputFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputFile, json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Booking DB audit result could not be written: " + outputFile, exception);
        }
    }

    private record DatabaseCredentials(String url, String username, String password, String driverClassName) {
        private static DatabaseCredentials fromEnvironment(final Map<String, String> environment) {
            final Map<String, String> missing = new LinkedHashMap<>();
            final String url = required(environment, DB_URL_ENV, missing);
            final String username = required(environment, DB_USERNAME_ENV, missing);
            final String password = required(environment, DB_PASSWORD_ENV, missing);
            if (!missing.isEmpty()) {
                throw new IllegalStateException("DB audit requires environment variables: "
                        + String.join(", ", missing.keySet()));
            }
            final String configuredDriver = environment.get(DB_DRIVER_ENV);
            final String driver = configuredDriver == null || configuredDriver.isBlank()
                    ? DEFAULT_DRIVER
                    : configuredDriver.trim();
            return new DatabaseCredentials(url, username, password, driver);
        }

        private static String required(
                final Map<String, String> environment,
                final String name,
                final Map<String, String> missing
        ) {
            final String value = environment.get(name);
            if (value == null || value.isBlank()) {
                missing.put(name, name);
                return "";
            }
            return value.trim();
        }
    }

    public record AuditResult(
            long performanceId,
            long clientSuccessOrders,
            long databaseOrders,
            long duplicateOrderSeats,
            long activeDuplicateHolds,
            long ordersWithoutSeats,
            long ordersWithoutCreatedHoldHistory,
            long duplicatePerformanceSeats
    ) {
        public boolean passed() {
            return clientSuccessOrders == databaseOrders
                    && duplicateOrderSeats == 0
                    && activeDuplicateHolds == 0
                    && ordersWithoutSeats == 0
                    && ordersWithoutCreatedHoldHistory == 0
                    && duplicatePerformanceSeats == 0;
        }

        public String failureSummary() {
            return "clientSuccessOrders=" + clientSuccessOrders
                    + ", databaseOrders=" + databaseOrders
                    + ", duplicateOrderSeats=" + duplicateOrderSeats
                    + ", activeDuplicateHolds=" + activeDuplicateHolds
                    + ", ordersWithoutSeats=" + ordersWithoutSeats
                    + ", ordersWithoutCreatedHoldHistory=" + ordersWithoutCreatedHoldHistory
                    + ", duplicatePerformanceSeats=" + duplicatePerformanceSeats;
        }
    }
}