package com.ticket.loadtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class LoadTestTokenValues {

    private LoadTestTokenValues() {
    }

    public static List<String> fromCsvOrFile(
            final String inlineValues,
            final String filePath,
            final String propertyName
    ) {
        final String rawValues = readRawValues(inlineValues, filePath, propertyName);
        final List<String> values = Stream.of(rawValues.split("[,\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        if (values.isEmpty()) {
            throw new IllegalStateException("System property must contain at least one value: -D" + propertyName);
        }
        return values;
    }

    private static String readRawValues(
            final String inlineValues,
            final String filePath,
            final String propertyName
    ) {
        if (filePath != null && !filePath.isBlank()) {
            final Path path = Path.of(filePath.trim());
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read token file for -D" + propertyName + ": " + path, exception);
            }
        }
        if (inlineValues == null || inlineValues.isBlank()) {
            throw new IllegalStateException("Missing required system property: -D" + propertyName);
        }
        return inlineValues;
    }
}
