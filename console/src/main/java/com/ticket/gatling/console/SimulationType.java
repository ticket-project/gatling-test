package com.ticket.gatling.console;

import java.util.Arrays;

public enum SimulationType {
    QUEUE_ENTER(
            "queue-enter",
            "대기열 진입",
            "com.ticket.loadtest.simulation.QueueEnterSimulation",
            "http://52.237.82.8:18090/legacy-queue",
            false,
            false,
            false,
            true
    ),
    LEGACY_QUEUE_STATUS(
            "legacy-queue-status",
            "레거시 대기열 상태",
            "com.ticket.loadtest.simulation.LegacyQueueStatusSimulation",
            "http://52.237.82.8:18090/legacy-queue",
            false,
            false,
            true,
            false
    ),
    CDN_PUBLIC_STATE(
            "cdn-public-state",
            "CDN Public State",
            "com.ticket.loadtest.simulation.CdnPublicStateSimulation",
            "http://52.237.82.8",
            false,
            false,
            true,
            false
    ),
    TICKET_OPEN_FLOW(
            "ticket-open-flow",
            "예매 오픈 흐름",
            "com.ticket.loadtest.simulation.TicketOpenFlowSimulation",
            "http://localhost:8080",
            true,
            false,
            true,
            true
    ),
    HOLD_RACE(
            "hold-race",
            "좌석 경합",
            "com.ticket.loadtest.simulation.HoldRaceSimulation",
            "http://localhost:8080",
            true,
            true,
            false,
            true
    ),
    TICKET_SERVER_CAPACITY(
            "ticket-server-capacity",
            "티켓 서버 용량",
            "com.ticket.loadtest.simulation.TicketServerCapacitySimulation",
            "http://localhost:8080",
            true,
            true,
            false,
            true
    );

    private final String key;
    private final String label;
    private final String className;
    private final String defaultBaseUrl;
    private final boolean usesSeatIds;
    private final boolean usesAdmissionTokens;
    private final boolean usesStatusPolling;
    private final boolean usesAccessTokens;

    SimulationType(
            final String key,
            final String label,
            final String className,
            final String defaultBaseUrl,
            final boolean usesSeatIds,
            final boolean usesAdmissionTokens,
            final boolean usesStatusPolling,
            final boolean usesAccessTokens
    ) {
        this.key = key;
        this.label = label;
        this.className = className;
        this.defaultBaseUrl = defaultBaseUrl;
        this.usesSeatIds = usesSeatIds;
        this.usesAdmissionTokens = usesAdmissionTokens;
        this.usesStatusPolling = usesStatusPolling;
        this.usesAccessTokens = usesAccessTokens;
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

    public static SimulationType fromKey(final String key) {
        return Arrays.stream(values())
                .filter(type -> type.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown simulation: " + key));
    }
}
