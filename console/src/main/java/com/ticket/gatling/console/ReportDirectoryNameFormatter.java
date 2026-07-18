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
                + "(" + targetName(targetUrl(request)) + ") "
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
            case BOOKING_CAPACITY -> "booking-capacity";
            case TICKET_OPEN_END_TO_END -> "ticket-open-e2e";
            case SEAT_CONTENTION -> "seat-contention";
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

    private static String targetUrl(final LoadTestRequest request) {
        return request.simulationType().usesBookingFeeder() ? request.coreBaseUrl() : request.baseUrl();
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
