package com.ticket.gatling.console;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public enum SimulationType {
    // label, statusPolling, accessTokens, bookingFeeder, queueBaseUrl, coreBookingFlow
    QUEUE_JOIN_ONLY("대기열 진입 요청", false, true, false, false, false),
    QUEUE_ENTER("대기열 입장 처리", false, true, false, false, false),
    CDN_PUBLIC_STATE("CDN 공개 대기열 상태 조회", true, false, false, false, false),
    TICKET_OPEN_END_TO_END("예매 오픈 전체 흐름", true, false, true, true, true),
    CORE_PERFORMANCE_SUMMARY_API("GET /api/v1/performances/{performanceId}/summary", false, false, false, false, true),
    CORE_SEAT_STATUS_API("GET /api/v1/performances/{performanceId}/seats/status", false, true, false, false, true),
    CORE_SEAT_SELECT_API("POST /api/v1/performances/{performanceId}/seats/{seatId}/select", false, true, true, false, true),
    CORE_ORDER_CREATE_API("POST /api/v1/orders (좌석 선택 후)", false, true, true, false, true),
    CORE_ORDER_GET_API("GET /api/v1/orders/{orderKey}", false, true, true, false, true),
    SMOKE("01 기본 예매 동작 확인", false, false, true, false, true),
    HOT_SEAT_CONCURRENCY("02 인기 좌석 동시 경합", false, false, true, false, true),
    CORE_ADMISSION_CAPACITY("03 고정 조건 Core 수용량", false, true, true, false, true),
    CORE_REALISTIC_CONTENTION("03-2 현실형 인기 좌석 경합", false, true, false, false, true),
    CORE_REALISTIC_USER_MIX("03-3 실제 사용자 흐름 혼합", false, true, false, false, true),
    CORE_ACTIVE_USERS_CLOSED("04 Core 동시 사용자 한계", false, false, true, false, true),
    CORE_SPIKE("05 Core 순간 부하 및 회복", false, false, true, false, true),
    QUEUE_PROTECTS_CORE("06 Queue의 Core 보호", true, false, true, true, true);

    private static final String CLASS_PREFIX = "com.ticket.loadtest.simulation.";

    private final String label;
    private final boolean usesStatusPolling;
    private final boolean usesAccessTokens;
    private final boolean usesBookingFeeder;
    private final boolean usesQueueBaseUrl;
    private final boolean usesCoreBookingFlow;

    SimulationType(
            final String label,
            final boolean usesStatusPolling,
            final boolean usesAccessTokens,
            final boolean usesBookingFeeder,
            final boolean usesQueueBaseUrl,
            final boolean usesCoreBookingFlow
    ) {
        this.label = label;
        this.usesStatusPolling = usesStatusPolling;
        this.usesAccessTokens = usesAccessTokens;
        this.usesBookingFeeder = usesBookingFeeder;
        this.usesQueueBaseUrl = usesQueueBaseUrl;
        this.usesCoreBookingFlow = usesCoreBookingFlow;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public String label() {
        return label;
    }

    /** QUEUE_JOIN_ONLY -> com.ticket.loadtest.simulation.QueueJoinOnlySimulation */
    public String className() {
        return Arrays.stream(name().split("_"))
                .map(word -> word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining("", CLASS_PREFIX, "Simulation"));
    }

    public boolean usesStatusPolling() {
        return usesStatusPolling;
    }

    public boolean usesAccessTokens() {
        return usesAccessTokens;
    }

    public boolean usesFeederAccessTokens() {
        return this == CORE_SEAT_SELECT_API || this == CORE_ORDER_CREATE_API;
    }

    /** 원격 대상에서 JWT sub로 쓸 실제 회원 ID 파일이 필요한 시나리오. */
    public boolean usesExistingMemberIds() {
        return switch (this) {
            case CORE_SEAT_STATUS_API, CORE_SEAT_SELECT_API, CORE_ORDER_CREATE_API,
                    CORE_ADMISSION_CAPACITY, CORE_REALISTIC_CONTENTION, CORE_REALISTIC_USER_MIX -> true;
            default -> false;
        };
    }

    public boolean usesBookingFeeder() {
        return usesBookingFeeder;
    }

    public boolean usesCoreBookingFlow() {
        return usesCoreBookingFlow;
    }

    public boolean usesQueueBaseUrl() {
        return usesQueueBaseUrl;
    }

    public String bookingScenario() {
        return usesCoreBookingFlow ? name() : "";
    }

    public static SimulationType fromKey(final String key) {
        return Arrays.stream(values())
                .filter(type -> type.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown simulation: " + key));
    }
}
