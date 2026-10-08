package com.ticket.loadtest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/** 로컬 Core와 같은 PostgreSQL에서 측정 조건과 실제 테스트 회원 ID를 읽는다. */
public final class LocalPostgresSnapshot {
    private LocalPostgresSnapshot() {
    }

    public static void main(final String[] args) throws Exception {
        final String url = environment("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/ticket");
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("Local capacity measurements require PostgreSQL");
        }
        final URI target = URI.create(url.substring("jdbc:".length()));
        if (!java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(target.getHost())) {
            throw new IllegalArgumentException("Local snapshot requires a loopback PostgreSQL host");
        }
        final Path output = Path.of(System.getProperty("localSnapshotDir"));
        final int memberCount = Integer.parseInt(System.getProperty("localSnapshotMembers"));
        Files.createDirectories(output);
        try (Connection connection = DriverManager.getConnection(url,
                environment("SPRING_DATASOURCE_USERNAME", "ticket"),
                environment("SPRING_DATASOURCE_PASSWORD", "ticket_local"))) {
            connection.setReadOnly(true);
            final Properties snapshot = new Properties();
            snapshot.setProperty("product", connection.getMetaData().getDatabaseProductName());
            snapshot.setProperty("version", connection.getMetaData().getDatabaseProductVersion());
            snapshot.setProperty("database", connection.getCatalog());
            for (String table : java.util.List.of("members", "orders")) {
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(30);
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        rows.next();
                        snapshot.setProperty(table, Long.toString(rows.getLong(1)));
                    }
                }
            }
            try (var writer = Files.newBufferedWriter(output.resolve("database.properties"), StandardCharsets.UTF_8)) {
                snapshot.store(writer, "Local PostgreSQL measurement conditions");
            }
            try (var statement = connection.prepareStatement("""
                    SELECT id FROM members
                    WHERE deleted_at IS NULL AND email LIKE 'loadtest%@test.com' AND role = 'MEMBER'
                    ORDER BY id LIMIT ?
                    """)) {
                statement.setInt(1, memberCount);
                statement.setQueryTimeout(30);
                try (var rows = statement.executeQuery();
                        var writer = Files.newBufferedWriter(output.resolve("member-ids.txt"), StandardCharsets.UTF_8)) {
                    int found = 0;
                    while (rows.next()) {
                        writer.write(Long.toString(rows.getLong(1)));
                        writer.newLine();
                        found++;
                    }
                    if (found != memberCount) {
                        throw new IllegalStateException("Not enough local test members: " + found + " of " + memberCount);
                    }
                }
            }
        }
    }

    private static String environment(final String name, final String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
