package com.ticket.loadtest.simulation;

import com.ticket.loadtest.BookingEvidenceRecorder;
import com.ticket.loadtest.BookingResultRecorder;
import com.ticket.loadtest.CoreRejections;
import com.ticket.loadtest.LoadTestConfig;
import com.ticket.loadtest.RealisticSeatSelection;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import io.gatling.javaapi.http.WsConnectActionBuilder;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.regex;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static io.gatling.javaapi.http.HttpDsl.ws;

final class CoreBookingFlow {
    private static final String SEAT_SOCKET = "seatSocket";
    private static final Duration SEAT_SOCKET_TIMEOUT = Duration.ofSeconds(5);
    private static final String STOMP_CONNECT =
            "CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer #{accessToken}\n\n\u0000";
    private static final String STOMP_SUBSCRIBE_SEATS =
            "SUBSCRIBE\nid:seats-0\ndestination:/topic/performance/#{performanceId}/seats\n\n\u0000";
    private CoreBookingFlow() {
    }

    static ChainBuilder initializeSession(final String scenario) {
        return initializeSession(scenario, false);
    }

    static ChainBuilder initializeRealisticSession(final String scenario) {
        return initializeSession(scenario, true);
    }

    private static ChainBuilder initializeSession(final String scenario, final boolean dynamicSeatSelection) {
        return exec(session -> {
            final Instant startedAt = BookingEvidenceRecorder.recordStarted(
                    Path.of(LoadTestConfig.resultFile()),
                    scenario,
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId")
            );
            Session initialized = session
                    .removeAll("terminalResult", "terminalHttpStatus", "orderKey", "coreAdmittedAt")
                    .set("bookingScenarioName", scenario)
                    .set("performanceId", LoadTestConfig.performanceId())
                    .set("flowStartedAt", startedAt.toString())
                    .set("lastStep", "INITIALIZED")
                    .set("dynamicSeatSelection", dynamicSeatSelection)
                    .set("selectAttemptCount", 0);
            if (dynamicSeatSelection) {
                return initialized
                        .removeAll("seatId", "seatIdsJson", "availableSeatIds", "selectConflict")
                        .set("attemptedSeatIds", Set.<Long>of());
            }
            return initialized
                    .set("seatIdsJson", "[" + session.getLong("seatId") + "]");
        });
    }

    static ChainBuilder successfulFlow(final String scenario, final boolean verifyCreatedOrder) {
        return successfulFlow(scenario, verifyCreatedOrder, true);
    }

    static ChainBuilder successfulFlowWithoutAdmission(final String scenario, final boolean verifyCreatedOrder) {
        return successfulFlow(scenario, verifyCreatedOrder, false);
    }

    private static ChainBuilder successfulFlow(
            final String scenario,
            final boolean verifyCreatedOrder,
            final boolean includeAdmissionToken
    ) {
        ChainBuilder flow = recordCoreAdmission()
                .exec(fetchPerformanceSummary())
                .exec(captureExpectedStatus("PERFORMANCE_SUMMARY", "performanceSummaryHttpStatus", 200))
                .exec(refreshSeatStatus(includeAdmissionToken))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(selectSeat(includeAdmissionToken, false))
                                .exec(captureExpectedStatus("SELECT_SEAT", "selectHttpStatus", 200, "selectErrorCode"))
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(createOrder(includeAdmissionToken, false))
                                .exec(captureExpectedStatus("CREATE_ORDER", "createOrderHttpStatus", 201, "orderErrorCode"))
                                .exec(doIf(CoreBookingFlow::canContinue).then(resolveOrderKey()))
                ));

        if (verifyCreatedOrder) {
            flow = flow.exec(doIf(CoreBookingFlow::canContinue).then(verifyOrder()));
        }
        return flow.exec(recordTerminal(scenario, verifyCreatedOrder));
    }

    static ChainBuilder realisticFlow(final String scenario) {
        return realisticFlow(scenario, true);
    }

    static ChainBuilder realisticFlowWithoutAdmission(final String scenario) {
        return realisticFlow(scenario, false);
    }

    private static ChainBuilder realisticFlow(final String scenario, final boolean includeAdmissionToken) {
        return recordCoreAdmission()
                .exec(fetchPerformanceSummary())
                .exec(captureExpectedStatus("PERFORMANCE_SUMMARY", "performanceSummaryHttpStatus", 200))
                .exec(refreshSeatStatus(includeAdmissionToken))
                .exec(selectSeatWithRetry(includeAdmissionToken))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        pause(LoadTestConfig.bookingOrderThinkMin(), LoadTestConfig.bookingOrderThinkMax())
                ))
                .exec(doIf(session -> canContinue(session) && shouldDropBeforeOrder()).then(
                        markUserDropout()
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(orderAttempt(includeAdmissionToken)))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        pause(LoadTestConfig.bookingRetryThinkMin(), LoadTestConfig.bookingRetryThinkMax())
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(verifyOrder()))
                .exec(recordTerminal(scenario, true));
    }

    /**
     * 실제 사용자 흐름 혼합(03-3). 03-2 현실형 흐름에 실제 화면이 하는 네 가지를 더한다.
     *
     * <ul>
     *   <li>좌석 알림 WebSocket: 예매 화면이 열려 있는 동안 STOMP로 회차 좌석 topic을 구독한 채 연결을 유지한다.
     *   <li>좌석 배치도: 화면이 처음 불러오는 venue-layout과 공연 좌석 목록을 조회한다.
     *   <li>전체 해제: 주문 전에 이탈하는 사용자는 화면을 떠나며 선택을 전부 해제한다(FE leave guard).
     *   <li>주문 취소: 주문을 만든 사용자 일부가 결제 전에 취소한다.
     * </ul>
     */
    static ChainBuilder realisticUserMixFlow(final String scenario) {
        return exec(session -> session.set("showId", LoadTestConfig.showId()))
                .exec(recordCoreAdmission())
                .exec(openSeatSocket())
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(fetchPerformanceSummary())
                                .exec(captureExpectedStatus("PERFORMANCE_SUMMARY", "performanceSummaryHttpStatus", 200))
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(fetchSeatLayout()))
                .exec(doIf(CoreBookingFlow::canContinue).then(refreshSeatStatus(false)))
                .exec(selectSeatWithRetry(false))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        pause(LoadTestConfig.bookingOrderThinkMin(), LoadTestConfig.bookingOrderThinkMax())
                ))
                .exec(doIf(session -> canContinue(session) && shouldDropBeforeOrder()).then(
                        exec(releaseAllSelections())
                                .exec(doIf(CoreBookingFlow::canContinue).then(markUserDropout()))
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(orderAttempt(false)))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        pause(LoadTestConfig.bookingRetryThinkMin(), LoadTestConfig.bookingRetryThinkMax())
                ))
                .exec(doIf(session -> canContinue(session) && shouldCancelOrder()).then(cancelOrder()))
                .exec(doIf(CoreBookingFlow::canContinue).then(verifyOrder()))
                .exec(closeSeatSocket())
                .exec(recordTerminal(scenario, true));
    }

    private static ChainBuilder selectSeatWithRetry(final boolean includeAdmissionToken) {
        return exec(doIf(CoreBookingFlow::canContinue).then(
                        pause(LoadTestConfig.bookingSeatThinkMin(), LoadTestConfig.bookingSeatThinkMax())
                ))
                .exec(doIf(session -> canContinue(session) && shouldRefreshSeatStatus()).then(
                        refreshSeatStatus(includeAdmissionToken)
                ))
                .exec(selectAttempt(includeAdmissionToken))
                .asLongAs(CoreBookingFlow::shouldRetrySeatSelection).on(
                        pause(LoadTestConfig.bookingRetryThinkMin(), LoadTestConfig.bookingRetryThinkMax())
                                .exec(refreshSeatStatus(includeAdmissionToken))
                                .exec(selectAttempt(includeAdmissionToken))
                )
                .exec(doIf(CoreBookingFlow::hasSelectConflict).then(
                        markSelectBusinessRejection()
                ));
    }

    /** 좌석 한 번 고르기. 첫 시도와 재시도가 같은 몸체를 쓴다. */
    private static ChainBuilder selectAttempt(final boolean includeAdmissionToken) {
        return exec(doIf(session -> canContinue(session) && isDynamicSeatSelection(session)).then(
                        chooseAvailableSeat()
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(selectSeat(includeAdmissionToken, true))
                                .exec(classifySelectAttempt())
                ))
                .exec(doIf(CoreBookingFlow::hasSelectConflict).then(
                        recordSeatSelectionConflict()
                ));
    }

    private static ChainBuilder orderAttempt(final boolean includeAdmissionToken) {
        return exec(createOrder(includeAdmissionToken, true))
                .exec(classifyOrderAttempt())
                .exec(doIf(CoreBookingFlow::canContinue).then(resolveOrderKey()));
    }

    private static ChainBuilder refreshSeatStatus(final boolean includeAdmissionToken) {
        return exec(fetchSeatStatus(includeAdmissionToken))
                .exec(captureExpectedStatus("SEAT_STATUS", "seatStatusHttpStatus", 200));
    }

    private static ChainBuilder verifyOrder() {
        return exec(fetchOrder())
                .exec(captureExpectedStatus("GET_ORDER", "orderHttpStatus", 200))
                .exec(doIf(CoreBookingFlow::canContinue).then(validateOrderState()));
    }

    /**
     * Core의 /ws는 SockJS 엔드포인트라 브라우저 FE는 SockJS로 붙는다. 부하 발생기는 SockJS가 함께 여는 순수 WebSocket 경로(/ws/websocket)로 붙고
     * 그 위에 STOMP 프레임을 직접 보낸다. 서버 쪽 비용(세션·구독·브로드캐스트)은 같다.
     */
    private static ChainBuilder openSeatSocket() {
        WsConnectActionBuilder connect = ws("seat socket connect", SEAT_SOCKET).connect("/ws/websocket");
        if (!LoadTestConfig.wsOrigin().isBlank()) {
            connect = connect.header("Origin", LoadTestConfig.wsOrigin());
        }
        return exec(session -> session.set("lastStep", "SEAT_SOCKET").set("seatSocketOpen", false))
                .exec(connect)
                .exec(doIf(session -> !session.isFailed()).then(
                        exec(ws("stomp connect", SEAT_SOCKET)
                                .sendText(STOMP_CONNECT)
                                .await(SEAT_SOCKET_TIMEOUT).on(
                                        ws.checkTextMessage("stomp connected").check(regex("^CONNECTED"))
                                ))
                ))
                .exec(doIf(session -> !session.isFailed()).then(
                        exec(ws("stomp subscribe seats", SEAT_SOCKET).sendText(STOMP_SUBSCRIBE_SEATS))
                ))
                .exec(session -> session.isFailed()
                        ? terminalFailure(session, "TECHNICAL_SEAT_SOCKET_CONNECT", "SEAT_SOCKET", 0)
                        : session.set("seatSocketOpen", true));
    }

    private static ChainBuilder closeSeatSocket() {
        return doIf(session -> session.contains("seatSocketOpen") && session.getBoolean("seatSocketOpen")).then(
                exec(ws("seat socket close", SEAT_SOCKET).close())
                        .exec(session -> session.set("seatSocketOpen", false))
        );
    }

    /** 예매 화면이 처음 불러오는 배치도. 화면은 토큰 없이 부르므로 인증 헤더를 보내지 않는다. */
    private static ChainBuilder fetchSeatLayout() {
        return exec(session -> session
                        .removeAll("venueLayoutHttpStatus", "showSeatsHttpStatus")
                        .set("lastStep", "SEAT_LAYOUT"))
                .exec(http("venue layout")
                        .get("/api/v1/shows/#{showId}/venue-layout")
                        .headers(LoadTestConfig.bookingCorrelationHeaders())
                        .check(status().saveAs("venueLayoutHttpStatus"))
                        .check(status().is(200)))
                .exec(captureExpectedStatus("VENUE_LAYOUT", "venueLayoutHttpStatus", 200))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(http("show seat map")
                                .get("/api/v1/shows/#{showId}/seats")
                                .headers(LoadTestConfig.bookingCorrelationHeaders())
                                .check(status().saveAs("showSeatsHttpStatus"))
                                .check(status().is(200)))
                                .exec(captureExpectedStatus("SHOW_SEAT_MAP", "showSeatsHttpStatus", 200))
                ));
    }

    /** 주문 전에 화면을 떠나는 사용자는 선택한 좌석을 전부 해제한다. */
    private static ChainBuilder releaseAllSelections() {
        return exec(session -> session.remove("releaseAllHttpStatus").set("lastStep", "RELEASE_ALL_SELECTIONS"))
                .exec(http("release all selections")
                        .delete("/api/v1/performances/#{performanceId}/seats/select")
                        .headers(bookingHeaders(false))
                        .check(status().saveAs("releaseAllHttpStatus"))
                        .check(status().is(200)))
                .exec(captureExpectedStatus("RELEASE_ALL_SELECTIONS", "releaseAllHttpStatus", 200));
    }

    /** 결제 전에 주문을 취소한다. 취소는 좌석 선점 해제를 뒤따르게 한다. */
    private static ChainBuilder cancelOrder() {
        return exec(session -> session.remove("cancelOrderHttpStatus").set("lastStep", "CANCEL_ORDER"))
                .exec(http("cancel order")
                        .delete("/api/v1/orders/#{orderKey}")
                        .headers(bookingHeaders(false))
                        .check(status().saveAs("cancelOrderHttpStatus"))
                        .check(status().is(200)))
                .exec(captureExpectedStatus("CANCEL_ORDER", "cancelOrderHttpStatus", 200))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(session -> session
                                .set("terminalResult", "USER_DROPPED_ORDER_CANCELED")
                                .set("terminalHttpStatus", session.getInt("cancelOrderHttpStatus"))
                                .set("lastStep", "CANCEL_ORDER"))
                ));
    }

    static ChainBuilder recordCoreAdmission() {
        return exec(session -> {
            final Instant admittedAt = BookingEvidenceRecorder.recordCoreAdmission(
                    Path.of(LoadTestConfig.resultFile()),
                    session.getString("bookingScenarioName"),
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId")
            );
            return session
                    .set("coreAdmittedAt", admittedAt.toString())
                    .set("lastStep", "CORE_ADMITTED");
        });
    }

    private static ChainBuilder recordSeatSelectionConflict() {
        return exec(session -> {
            BookingEvidenceRecorder.recordSeatSelectionConflict(
                    Path.of(LoadTestConfig.resultFile()),
                    session.getString("bookingScenarioName"),
                    LoadTestConfig.nodeIndex()
            );
            return session;
        });
    }

    private static ChainBuilder fetchPerformanceSummary() {
        return exec(session -> session.remove("performanceSummaryHttpStatus").set("lastStep", "PERFORMANCE_SUMMARY"))
                .exec(http("performance summary")
                        .get("/api/v1/performances/#{performanceId}/summary")
                        .headers(bookingHeaders(false))
                        .check(status().saveAs("performanceSummaryHttpStatus"))
                        .check(status().is(200)));
    }

    static ChainBuilder fetchSeatStatus(final boolean includeAdmissionToken) {
        return exec(session -> session.removeAll("seatStatusHttpStatus", "availableSeatIds")
                        .set("lastStep", "SEAT_STATUS"))
                .exec(http("seat status")
                        .get("/api/v1/performances/#{performanceId}/seats/status")
                        .headers(bookingHeaders(includeAdmissionToken))
                        .check(status().saveAs("seatStatusHttpStatus"))
                        .check(status().is(200))
                        .check(jsonPath("$.data.seats[?(@.status == 'AVAILABLE')].seatId")
                                .ofLong().findAll().optional().saveAs("availableSeatIds")));
    }

    private static ChainBuilder chooseAvailableSeat() {
        return exec(session -> {
            final List<Long> available = session.contains("availableSeatIds")
                    ? session.<Long>getList("availableSeatIds")
                    : List.of();
            final Set<Long> attempted = session.contains("attemptedSeatIds")
                    ? session.<Long>getSet("attemptedSeatIds")
                    : Set.of();
            final List<Long> candidates = RealisticSeatSelection.availableCandidates(available, attempted);
            if (candidates.isEmpty()) {
                return session
                        .set("selectConflict", false)
                        .set("terminalResult", "BUSINESS_REJECTED_NO_AVAILABLE_SEAT")
                        .set("terminalHttpStatus", 409)
                        .set("lastStep", "SELECT_SEAT");
            }

            final long seatId = RealisticSeatSelection.chooseSeat(candidates);
            final Set<Long> updatedAttempts = new HashSet<>(attempted);
            updatedAttempts.add(seatId);
            return session
                    .set("attemptedSeatIds", Set.copyOf(updatedAttempts))
                    .set("seatId", seatId)
                    .set("seatIdsJson", "[" + seatId + "]")
                    .set("lastStep", "SELECT_SEAT");
        });
    }

    /** allowConflict면 분류된 409를 요청 성공으로 받고 시도 횟수를 센다. 아니면 200만 받는다. */
    static ChainBuilder selectSeat(final boolean includeAdmissionToken, final boolean allowConflict) {
        HttpRequestActionBuilder request = http("select seat")
                .post("/api/v1/performances/#{performanceId}/seats/#{seatId}/select")
                .headers(bookingHeaders(includeAdmissionToken))
                .check(status().saveAs("selectHttpStatus"))
                .check(jsonPath("$.error.code").optional().saveAs("selectErrorCode"))
                .check(allowConflict ? status().in(200, 409) : status().is(200));
        if (allowConflict) {
            request = request.checkIf((response, session) -> response.status().code() == 409).then(
                    jsonPath("$.error.code").in(CoreRejections.classifiedSelectCodes())
            );
        }
        return exec(session -> {
            final Session cleared = session.removeAll("selectHttpStatus", "selectErrorCode").set("lastStep", "SELECT_SEAT");
            return allowConflict
                    ? cleared.set("selectAttemptCount", optionalInt(session, "selectAttemptCount") + 1)
                    : cleared;
        }).exec(request);
    }

    private static ChainBuilder classifySelectAttempt() {
        return exec(session -> {
            if (!session.contains("selectHttpStatus")) {
                return terminalFailure(session, "TECHNICAL_SELECT_SEAT_NO_RESPONSE", "SELECT_SEAT", 0);
            }
            final int httpStatus = session.getInt("selectHttpStatus");
            if (httpStatus == 200) {
                return session.set("selectConflict", false);
            }
            final String errorCode = optionalString(session, "selectErrorCode");
            return switch (CoreRejections.ofSelect(httpStatus, errorCode)) {
                case BUSINESS_REJECTED -> session.set("selectConflict", true);
                case OVERLOADED -> overloaded(session, "SELECT_SEAT", httpStatus, errorCode);
                case TECHNICAL -> terminalFailure(session, "TECHNICAL_SELECT_SEAT_HTTP_" + httpStatus,
                        "SELECT_SEAT", httpStatus);
            };
        });
    }

    private static ChainBuilder markSelectBusinessRejection() {
        return exec(session -> session
                .set("terminalResult", CoreRejections.resultName(
                        CoreRejections.Kind.BUSINESS_REJECTED, optionalString(session, "selectErrorCode")))
                .set("terminalHttpStatus", optionalInt(session, "selectHttpStatus"))
                .set("lastStep", "SELECT_SEAT"));
    }

    /** allowConflict면 분류된 409를 요청 성공으로 받는다. 아니면 201만 받는다. */
    static ChainBuilder createOrder(final boolean includeAdmissionToken, final boolean allowConflict) {
        HttpRequestActionBuilder request = http("create order")
                .post("/api/v1/orders")
                .headers(bookingHeaders(includeAdmissionToken))
                .body(StringBody("""
                        {
                          "performanceId": #{performanceId},
                          "seatIds": #{seatIdsJson}
                        }
                        """))
                .check(status().saveAs("createOrderHttpStatus"))
                .check(allowConflict ? status().in(201, 409) : status().is(201))
                .check(header("X-Order-Key").optional().saveAs("orderKeyHeader"))
                .check(jsonPath("$.data.orderKey").optional().saveAs("orderKeyBody"))
                .check(jsonPath("$.error.code").optional().saveAs("orderErrorCode"));
        if (allowConflict) {
            request = request.checkIf((response, session) -> response.status().code() == 409).then(
                    jsonPath("$.error.code").in(CoreRejections.classifiedOrderCodes())
            );
        }
        return exec(session -> session
                .removeAll("createOrderHttpStatus", "orderKeyHeader", "orderKeyBody", "orderErrorCode")
                .set("lastStep", "CREATE_ORDER"))
                .exec(request);
    }

    private static ChainBuilder classifyOrderAttempt() {
        return exec(session -> {
            if (!session.contains("createOrderHttpStatus")) {
                return terminalFailure(session, "TECHNICAL_CREATE_ORDER_NO_RESPONSE", "CREATE_ORDER", 0);
            }
            final int httpStatus = session.getInt("createOrderHttpStatus");
            if (httpStatus == 201) {
                return session;
            }
            final String errorCode = optionalString(session, "orderErrorCode");
            return switch (CoreRejections.ofOrder(httpStatus, errorCode)) {
                case BUSINESS_REJECTED -> session
                        .set("terminalResult", CoreRejections.resultName(CoreRejections.Kind.BUSINESS_REJECTED, errorCode))
                        .set("terminalHttpStatus", httpStatus)
                        .set("lastStep", "CREATE_ORDER");
                case OVERLOADED -> overloaded(session, "CREATE_ORDER", httpStatus, errorCode);
                case TECHNICAL -> terminalFailure(session, "TECHNICAL_CREATE_ORDER_HTTP_" + httpStatus,
                        "CREATE_ORDER", httpStatus);
            };
        });
    }

    private static ChainBuilder markUserDropout() {
        return exec(session -> session
                .set("terminalResult", "USER_DROPPED_BEFORE_ORDER")
                .set("terminalHttpStatus", optionalInt(session, "selectHttpStatus"))
                .set("lastStep", "BEFORE_CREATE_ORDER"));
    }

    /** 헤더와 본문의 주문 키가 모두 비었거나 서로 다르면 기술 실패로 끝낸다. */
    static ChainBuilder resolveOrderKey() {
        return exec(session -> {
            final String headerOrderKey = optionalString(session, "orderKeyHeader");
            final String bodyOrderKey = optionalString(session, "orderKeyBody");
            if (headerOrderKey.isBlank() && bodyOrderKey.isBlank()) {
                return terminalFailure(session, "ORDER_KEY_MISSING", "CREATE_ORDER",
                        optionalInt(session, "createOrderHttpStatus"));
            }
            if (!headerOrderKey.isBlank() && !bodyOrderKey.isBlank() && !headerOrderKey.equals(bodyOrderKey)) {
                return terminalFailure(session, "ORDER_KEY_MISMATCH", "CREATE_ORDER",
                        optionalInt(session, "createOrderHttpStatus"));
            }
            return session.set("orderKey", headerOrderKey.isBlank() ? bodyOrderKey : headerOrderKey);
        });
    }

    static ChainBuilder fetchOrder() {
        return exec(session -> session
                .removeAll("orderHttpStatus", "orderState")
                .set("lastStep", "GET_ORDER"))
                .exec(http("get order")
                        .get("/api/v1/orders/#{orderKey}/status")
                        .headers(bookingHeaders(false))
                        .check(status().saveAs("orderHttpStatus"))
                        .check(status().is(200))
                        .check(jsonPath("$.data.status").optional().saveAs("orderState")));
    }

    private static ChainBuilder validateOrderState() {
        return exec(session -> "PENDING".equals(optionalString(session, "orderState"))
                ? session
                : terminalFailure(session, "ORDER_STATE_CONTRACT_FAILURE", "GET_ORDER",
                        optionalInt(session, "orderHttpStatus")));
    }

    private static ChainBuilder captureExpectedStatus(
            final String step,
            final String statusName,
            final int expectedStatus
    ) {
        return captureExpectedStatus(step, statusName, expectedStatus, null);
    }

    /** 고정 좌석 흐름은 409를 모두 실패로 보지만, E6003은 서버 과부하라 기술 실패와 나눠 센다. */
    private static ChainBuilder captureExpectedStatus(
            final String step,
            final String statusName,
            final int expectedStatus,
            final String errorCodeName
    ) {
        return exec(session -> {
            if (session.contains("terminalResult")) {
                return session;
            }
            if (!session.contains(statusName)) {
                return terminalFailure(session, "TECHNICAL_" + step + "_NO_RESPONSE", step, 0);
            }
            final int actualStatus = session.getInt(statusName);
            if (actualStatus != expectedStatus) {
                final String errorCode = errorCodeName == null ? "" : optionalString(session, errorCodeName);
                if (actualStatus == 409 && CoreRejections.OVERLOADED_CODE.equals(errorCode)) {
                    return overloaded(session, step, actualStatus, errorCode);
                }
                return terminalFailure(session, "TECHNICAL_" + step + "_HTTP_" + actualStatus,
                        step, actualStatus);
            }
            return session;
        });
    }

    private static ChainBuilder recordTerminal(final String scenario, final boolean verifyCreatedOrder) {
        return exec(session -> {
            final String result = session.contains("terminalResult")
                    ? session.getString("terminalResult")
                    : "SUCCESS";
            final String lastStep = session.contains("terminalResult")
                    ? session.getString("lastStep")
                    : "COMPLETED";
            final int httpStatus = session.contains("terminalHttpStatus")
                    ? session.getInt("terminalHttpStatus")
                    : optionalInt(session, verifyCreatedOrder ? "orderHttpStatus" : "createOrderHttpStatus");
            BookingResultRecorder.append(
                    Path.of(LoadTestConfig.resultFile()),
                    scenario,
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId"),
                    optionalLong(session, "seatId"),
                    optionalString(session, "orderKey"),
                    httpStatus,
                    result,
                    lastStep,
                    optionalString(session, "flowStartedAt"),
                    optionalString(session, "coreAdmittedAt"),
                    terminalErrorCode(session),
                    optionalInt(session, "selectAttemptCount")
            );
            return session;
        });
    }

    private static boolean hasSelectConflict(final Session session) {
        return canContinue(session)
                && session.contains("selectConflict")
                && session.getBoolean("selectConflict");
    }

    private static boolean shouldRetrySeatSelection(final Session session) {
        final int maxAttempts = RealisticSeatSelection.maxAttempts(isDynamicSeatSelection(session));
        return hasSelectConflict(session) && optionalInt(session, "selectAttemptCount") < maxAttempts;
    }


    private static boolean isDynamicSeatSelection(final Session session) {
        return session.contains("dynamicSeatSelection") && session.getBoolean("dynamicSeatSelection");
    }

    private static Map<CharSequence, String> bookingHeaders(final boolean includeAdmissionToken) {
        final Map<CharSequence, String> headers = new HashMap<>(includeAdmissionToken
                ? LoadTestConfig.authAndAdmissionHeaders()
                : LoadTestConfig.authHeaders());
        headers.putAll(LoadTestConfig.bookingCorrelationHeaders());
        return Map.copyOf(headers);
    }

    private static String terminalErrorCode(final Session session) {
        final String orderErrorCode = optionalString(session, "orderErrorCode");
        return orderErrorCode.isBlank() ? optionalString(session, "selectErrorCode") : orderErrorCode;
    }

    /** Queue state의 refreshAfterMs를 따르되 statusPollPause 범위와 jitter 안에 둔다. */
    static long queuePollDelayMs(final Session session) {
        final long configuredDelayMs = Duration.ofSeconds(LoadTestConfig.statusPollPauseSeconds()).toMillis();
        final long minimumDelayMs = LoadTestConfig.statusPollPauseMin().toMillis();
        final long maximumDelayMs = LoadTestConfig.statusPollPauseMax().toMillis();
        final long refreshAfterMs = session.contains("refreshAfterMs")
                ? Math.max(0L, session.getLong("refreshAfterMs"))
                : configuredDelayMs;
        final long jitterMs = Math.max(configuredDelayMs - minimumDelayMs, maximumDelayMs - configuredDelayMs);
        final long jitteredDelayMs = jitterMs == 0
                ? refreshAfterMs
                : refreshAfterMs + ThreadLocalRandom.current().nextLong(-jitterMs, jitterMs + 1);
        return Math.max(minimumDelayMs, Math.min(maximumDelayMs, jitteredDelayMs));
    }

    private static boolean shouldRefreshSeatStatus() {
        return chance(LoadTestConfig.bookingSeatRefreshPercent());
    }

    private static boolean shouldDropBeforeOrder() {
        return chance(LoadTestConfig.bookingDropoutPercent());
    }

    private static boolean shouldCancelOrder() {
        return chance(LoadTestConfig.bookingOrderCancelPercent());
    }

    private static boolean chance(final double percent) {
        return percent > 0.0 && ThreadLocalRandom.current().nextDouble(100.0) < percent;
    }

    static boolean canContinue(final Session session) {
        return !session.contains("terminalResult");
    }

    /** 과부하는 기술 실패가 아니라 별도 결과로 남긴다. BookingEvidenceRecorder가 따로 센다. */
    static Session overloaded(
            final Session session,
            final String lastStep,
            final int httpStatus,
            final String errorCode
    ) {
        return session
                .set("terminalResult", CoreRejections.resultName(CoreRejections.Kind.OVERLOADED, errorCode))
                .set("terminalHttpStatus", httpStatus)
                .set("lastStep", lastStep);
    }

    static Session terminalFailure(
            final Session session,
            final String result,
            final String lastStep,
            final int httpStatus
    ) {
        return session
                .set("terminalResult", result)
                .set("terminalHttpStatus", httpStatus)
                .set("lastStep", lastStep)
                .markAsFailed();
    }

    static int optionalInt(final Session session, final String key) {
        return session.contains(key) ? session.getInt(key) : 0;
    }

    private static long optionalLong(final Session session, final String key) {
        return session.contains(key) ? session.getLong(key) : 0L;
    }

    static String optionalString(final Session session, final String key) {
        return session.contains(key) ? session.getString(key) : "";
    }
}
