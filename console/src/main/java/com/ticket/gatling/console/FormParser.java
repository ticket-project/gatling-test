package com.ticket.gatling.console;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FormParser {
    private FormParser() {
    }

    static Map<String, List<String>> parse(final String body) {
        final Map<String, List<String>> values = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return values;
        }
        for (final String pair : body.split("&")) {
            final int separator = pair.indexOf('=');
            final String key = separator >= 0 ? pair.substring(0, separator) : pair;
            final String value = separator >= 0 ? pair.substring(separator + 1) : "";
            values.computeIfAbsent(decode(key), ignored -> new ArrayList<>()).add(decode(value));
        }
        return values;
    }

    private static String decode(final String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
