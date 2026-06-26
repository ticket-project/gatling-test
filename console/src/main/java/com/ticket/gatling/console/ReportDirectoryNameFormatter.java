package com.ticket.gatling.console;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.NumberFormat;
import java.util.Locale;

final class ReportDirectoryNameFormatter {
    private static final String INVALID_FILENAME_CHARS = "[<>:\"/\\\\|?*\\p{Cntrl}]";

    private ReportDirectoryNameFormatter() {
    }

    static String format(final LoadTestRequest request) {
        final String name = shortSimulationName(request.simulationType())
                + "(" + targetName(request.baseUrl()) + ") "
                + formatCount(displayExecutionCount(request))
                + "(" + executionDetail(request) + ")";
        return name.replaceAll(INVALID_FILENAME_CHARS, ".")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String shortSimulationName(final SimulationType simulationType) {
        return switch (simulationType) {
            case QUEUE_JOIN_ONLY -> "queue-join";
            case QUEUE_ENTER -> "queue";
            case LEGACY_QUEUE_STATUS -> "legacy-queue";
            case CDN_PUBLIC_STATE -> "cdn";
            case TICKET_OPEN_FLOW -> "ticket-open";
            case HOLD_RACE -> "hold-race";
            case TICKET_SERVER_CAPACITY -> "ticket-server";
        };
    }

    private static long displayExecutionCount(final LoadTestRequest request) {
        final long sessions = request.estimatedVirtualUsers();
        return switch (request.simulationType()) {
            case LEGACY_QUEUE_STATUS, CDN_PUBLIC_STATE -> sessions * request.statusPolls();
            default -> sessions;
        };
    }

    private static String formatCount(final long count) {
        if (count > 0 && count % 10_000 == 0) {
            return (count / 10_000) + "만";
        }
        return NumberFormat.getIntegerInstance(Locale.US).format(count);
    }

    private static String executionDetail(final LoadTestRequest request) {
        final String base = request.durationSeconds() + "초간" + request.estimatedVirtualUsers() + "명";
        if (request.simulationType().usesStatusPolling()) {
            return base + "이 " + request.statusPolls() + "번씩 "
                    + request.statusPollPauseSeconds() + "초 주기로";
        }
        return base;
    }

    private static String targetName(final String baseUrl) {
        try {
            final URI uri = new URI(baseUrl);
            if (uri.getHost() != null) {
                if (uri.getPort() >= 0) {
                    return uri.getHost() + "." + uri.getPort();
                }
                return uri.getHost();
            }
        } catch (URISyntaxException ignored) {
            // Fall back to plain sanitizing below.
        }
        return stripScheme(baseUrl)
                .replaceFirst("[/?#].*$", "")
                .replace(':', '.');
    }

    private static String stripScheme(final String value) {
        return value == null ? "" : value.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
    }
}
