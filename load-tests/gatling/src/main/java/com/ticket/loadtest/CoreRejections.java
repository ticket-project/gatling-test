package com.ticket.loadtest;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Core가 좌석 선택·주문 생성에 409로 돌려주는 응답을 셋으로 나눈다.
 *
 * <p>코드의 원본은 ticket-core의 {@code BookingErrorCode}와 {@code BookingExceptionHandler}다. Core의
 * {@code BookingExceptionHandlerTest}가 코드와 HTTP 상태를 외부 계약으로 고정한다.
 *
 * <ul>
 *   <li>비즈니스 거절: 좌석 경합, 진행 중 주문, 선점 한도, 선택하지 않은 좌석 주문처럼 서버가 정상적으로 "안 된다"고 답한 것.
 *   <li>과부하: E6003(선점 락 대기 초과). 서버가 요청 순서를 제때 처리하지 못한 것이다. 비즈니스 거절로 숨기지 않고
 *       기술 실패와도 섞지 않도록 따로 센다.
 *   <li>기술 실패: 위에 없는 409와 그 밖의 모든 상태.
 * </ul>
 */
public final class CoreRejections {
    public static final String OVERLOADED_CODE = "E6003";
    public static final String OVERLOADED_RESULT_PREFIX = "OVERLOADED_";
    public static final String BUSINESS_REJECTED_RESULT_PREFIX = "BUSINESS_REJECTED_";

    /** E4001 다른 사용자가 선택함, E6000 이미 선점됨, E6001 회원 선택 좌석 수가 선점 한도에 닿음. */
    private static final Set<String> SELECT_BUSINESS_CODES = Set.of("E4001", "E6000", "E6001");
    /**
     * E4006 본인이 선택 중인 좌석이 아님(선택 경합에서 진 사용자가 주문한 경우 등), E4007 선택 시간이 지나 풀린 좌석, E5004 진행 중인 결제 대기 주문, E6000 이미 선점됨,
     * E6001 선점 좌석 수 한도 초과.
     */
    private static final Set<String> ORDER_BUSINESS_CODES = Set.of("E4006", "E4007", "E5004", "E6000", "E6001");

    public enum Kind {
        BUSINESS_REJECTED,
        OVERLOADED,
        TECHNICAL
    }

    private CoreRejections() {
    }

    public static Kind ofSelect(final int httpStatus, final String errorCode) {
        return classify(httpStatus, errorCode, SELECT_BUSINESS_CODES);
    }

    public static Kind ofOrder(final int httpStatus, final String errorCode) {
        return classify(httpStatus, errorCode, ORDER_BUSINESS_CODES);
    }

    /** 선택 409에서 Gatling check가 받아들이는 코드. 과부하도 서버가 분류해 돌려준 응답이라 요청 KO로 세지 않는다. */
    public static List<String> classifiedSelectCodes() {
        return withOverloaded(SELECT_BUSINESS_CODES);
    }

    /** 주문 409에서 Gatling check가 받아들이는 코드. */
    public static List<String> classifiedOrderCodes() {
        return withOverloaded(ORDER_BUSINESS_CODES);
    }

    /** 사용자 흐름 결과 이름. 비즈니스 거절과 과부하만 이름을 만든다. */
    public static String resultName(final Kind kind, final String errorCode) {
        return switch (kind) {
            case BUSINESS_REJECTED -> BUSINESS_REJECTED_RESULT_PREFIX + errorCode;
            case OVERLOADED -> OVERLOADED_RESULT_PREFIX + errorCode;
            case TECHNICAL -> throw new IllegalArgumentException("기술 실패는 단계와 HTTP 상태로 이름을 만든다");
        };
    }

    private static Kind classify(final int httpStatus, final String errorCode, final Set<String> businessCodes) {
        if (httpStatus != 409 || errorCode == null) {
            return Kind.TECHNICAL;
        }
        if (businessCodes.contains(errorCode)) {
            return Kind.BUSINESS_REJECTED;
        }
        if (OVERLOADED_CODE.equals(errorCode)) {
            return Kind.OVERLOADED;
        }
        return Kind.TECHNICAL;
    }

    private static List<String> withOverloaded(final Set<String> businessCodes) {
        return Stream.concat(businessCodes.stream(), Stream.of(OVERLOADED_CODE)).sorted().toList();
    }
}
