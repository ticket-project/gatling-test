package com.ticket.gatling.console;

import java.util.Arrays;

public enum SimulationType {
    QUEUE_JOIN_ONLY(
            "queue-join-only",
            "대기열 진입 요청",
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
            "대기열 입장 처리",
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
            "기존 대기열 상태 조회",
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
            "CDN 공개 대기열 상태 조회",
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
            "고정 좌석 예매 처리량",
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
            "예매 오픈 전체 흐름",
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
            "동일 좌석 경합",
            "com.ticket.loadtest.simulation.SeatContentionSimulation",
            "",
            false,
            false,
            false,
            false,
            true,
            false,
            "SEAT_CONTENTION"
    ),
    CORE_PERFORMANCE_SUMMARY_API(
            "core-performance-summary-api",
            "GET /api/v1/performances/{performanceId}/summary",
            "com.ticket.loadtest.simulation.CorePerformanceSummaryApiSimulation",
            "",
            false,
            false,
            false,
            false,
            false,
            false,
            "CORE_PERFORMANCE_SUMMARY_API"
    ),
    CORE_SEAT_STATUS_API(
            "core-seat-status-api",
            "GET /api/v1/performances/{performanceId}/seats/status",
            "com.ticket.loadtest.simulation.CoreSeatStatusApiSimulation",
            "",
            false,
            false,
            false,
            true,
            false,
            false,
            "CORE_SEAT_STATUS_API"
    ),
    CORE_SEAT_SELECT_API(
            "core-seat-select-api",
            "POST /api/v1/performances/{performanceId}/seats/{seatId}/select",
            "com.ticket.loadtest.simulation.CoreSeatSelectApiSimulation",
            "",
            false,
            false,
            false,
            false,
            true,
            false,
            "CORE_SEAT_SELECT_API"
    ),
    CORE_ORDER_CREATE_API(
            "core-order-create-api",
            "POST /api/v1/orders",
            "com.ticket.loadtest.simulation.CoreOrderCreateApiSimulation",
            "",
            false,
            false,
            false,
            false,
            true,
            false,
            "CORE_ORDER_CREATE_API"
    ),
    CORE_ORDER_GET_API(
            "core-order-get-api",
            "GET /api/v1/orders/{orderKey}",
            "com.ticket.loadtest.simulation.CoreOrderGetApiSimulation",
            "",
            false,
            false,
            false,
            false,
            true,
            false,
            "CORE_ORDER_GET_API"
    ),
    SMOKE(
            "smoke",
            "01 기본 예매 동작 확인",
            "com.ticket.loadtest.simulation.SmokeSimulation",
            "",
            false,
            false,
            false,
            false,
            true,
            false,
            "SMOKE"
    ),
    HOT_SEAT_CONCURRENCY(
            "hot-seat-concurrency",
            "02 인기 좌석 동시 경합",
            "com.ticket.loadtest.simulation.HotSeatConcurrencySimulation",
            "",
            false,
            false,
            false,
            false,
            true,
            false,
            "HOT_SEAT_CONCURRENCY"
    ),
    CORE_ADMISSION_CAPACITY(
            "core-admission-capacity",
            "03 고정 조건 Core 수용량",
            "com.ticket.loadtest.simulation.CoreAdmissionCapacitySimulation",
            "",
            false,
            false,
            false,
            false,
            true,
            false,
            "CORE_ADMISSION_CAPACITY"
    ),
    CORE_REALISTIC_CONTENTION(
            "core-realistic-contention",
            "03-2 현실형 인기 좌석 경합",
            "com.ticket.loadtest.simulation.CoreRealisticContentionSimulation",
            "",
            false,
            false,
            false,
            true,
            false,
            false,
            "CORE_REALISTIC_CONTENTION"
    ),
    CORE_ACTIVE_USERS_CLOSED(
            "core-active-users-closed",
            "04 Core 동시 사용자 한계",
            "com.ticket.loadtest.simulation.CoreActiveUsersClosedSimulation",
            "",
            false,
            false,
            false,
            false,
            true,
            false,
            "CORE_ACTIVE_USERS_CLOSED"
    ),
    CORE_SPIKE(
            "core-spike",
            "05 Core 순간 부하 및 회복",
            "com.ticket.loadtest.simulation.CoreSpikeSimulation",
            "",
            false,
            false,
            false,
            false,
            true,
            false,
            "CORE_SPIKE"
    ),
    QUEUE_PROTECTS_CORE(
            "queue-protects-core",
            "06 Queue의 Core 보호",
            "com.ticket.loadtest.simulation.QueueProtectsCoreSimulation",
            "",
            false,
            false,

            true,
            false,
            true,
            true,
            "QUEUE_PROTECTS_CORE"
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

    public boolean usesCoreBookingFlow() {
        return !bookingScenario.isBlank();
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