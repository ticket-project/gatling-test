package com.ticket.gatling.console;

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
            values.computeIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8), ignored -> new ArrayList<>())
                    .add(URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }
}
