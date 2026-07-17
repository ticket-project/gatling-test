package com.ticket.gatling.console;

import java.util.Arrays;

public enum SimulationType {
    QUEUE_JOIN_ONLY(
            "queue-join-only",
            "Queue Join Only",
            "com.ticket.loadtest.simulation.QueueJoinOnlySimulation",
            "https://queue.oneticket.site",
            false,
            false,
            false,
            true,
            false,
            false,
            ""
    ),
    QUEUE_ENTER(
            "queue-enter",
            "Queue Enter",
            "com.ticket.loadtest.simulation.QueueEnterSimulation",
            "http://52.237.82.8:18090/legacy-queue",
            false,
            false,
            false,
            true,
            false,
            false,
            ""
    ),
    LEGACY_QUEUE_STATUS(
            "legacy-queue-status",
            "Legacy Queue Status",
            "com.ticket.loadtest.simulation.LegacyQueueStatusSimulation",
            "http://52.237.82.8:18090/legacy-queue",
            false,
            false,
            true,
            false,
            false,
            false,
            ""
    ),
    CDN_PUBLIC_STATE(
            "cdn-public-state",
            "CDN Public State",
            "com.ticket.loadtest.simulation.CdnPublicStateSimulation",
            "https://queue.oneticket.site",
            false,
            false,
            true,
            false,
            false,
            false,
            ""
    ),
    BOOKING_CAPACITY(
            "booking-capacity",
            "Booking Capacity",
            "com.ticket.loadtest.simulation.BookingCapacitySimulation",
            "",
            false,
            false,
            false,
            false,
            true,
            false,
            "BOOKING_CAPACITY"
    ),
    TICKET_OPEN_END_TO_END(
            "ticket-open-end-to-end",
            "Ticket Open End to End",
            "com.ticket.loadtest.simulation.TicketOpenEndToEndSimulation",
            "",
            false,
            false,
            true,
            false,
            true,
            true,
            "TICKET_OPEN_END_TO_END"
    ),
    SEAT_CONTENTION(
            "seat-contention",
            "Seat Contention",
            "com.ticket.loadtest.simulation.SeatContentionSimulation",
            "",
            false,
            false,
            false,
            false,
            true,
            false,
            "SEAT_CONTENTION"
    );

    private final String key;
    private final String label;
    private final String className;
    private final String defaultBaseUrl;
    private final boolean usesSeatIds;
    private final boolean usesAdmissionTokens;
    private final boolean usesStatusPolling;
    private final boolean usesAccessTokens;
    private final boolean usesBookingFeeder;
    private final boolean usesQueueBaseUrl;
    private final String bookingScenario;

    SimulationType(
            final String key,
            final String label,
            final String className,
            final String defaultBaseUrl,
            final boolean usesSeatIds,
            final boolean usesAdmissionTokens,
            final boolean usesStatusPolling,
            final boolean usesAccessTokens,
            final boolean usesBookingFeeder,
            final boolean usesQueueBaseUrl,
            final String bookingScenario
    ) {
        this.key = key;
        this.label = label;
        this.className = className;
        this.defaultBaseUrl = defaultBaseUrl;
        this.usesSeatIds = usesSeatIds;
        this.usesAdmissionTokens = usesAdmissionTokens;
        this.usesStatusPolling = usesStatusPolling;
        this.usesAccessTokens = usesAccessTokens;
        this.usesBookingFeeder = usesBookingFeeder;
        this.usesQueueBaseUrl = usesQueueBaseUrl;
        this.bookingScenario = bookingScenario;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String className() {
        return className;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    public boolean usesSeatIds() {
        return usesSeatIds;
    }

    public boolean usesAdmissionTokens() {
        return usesAdmissionTokens;
    }

    public boolean usesStatusPolling() {
        return usesStatusPolling;
    }

    public boolean usesAccessTokens() {
        return usesAccessTokens;
    }

    public boolean usesBookingFeeder() {
        return usesBookingFeeder;
    }

    public boolean usesQueueBaseUrl() {
        return usesQueueBaseUrl;
    }

    public String bookingScenario() {
        return bookingScenario;
    }

    public static SimulationType fromKey(final String key) {
        return Arrays.stream(values())
                .filter(type -> type.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown simulation: " + key));
    }
}