package com.ticket.loadtest.simulation;

import com.ticket.loadtest.BookingResultRecorder;
import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.nio.file.Path;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.dummy;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class TicketOpenEndToEndSimulation extends Simulation {

    private static final String SCENARIO = "TICKET_OPEN_END_TO_END";
    private static final Duration ORDER_POLL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ORDER_POLL_PAUSE = Duration.ofMillis(200);

    private final HttpProtocolBuilder httpProtocol = Protocols.json(LoadTestConfig.coreBaseUrl());

    public TicketOpenEndToEndSimulation() {
        final Duration queuePollTimeout = Duration.ofSeconds(LoadTestConfig.pollingTimeoutSeconds());
        final ScenarioBuilder scenario = scenario("예매 오픈 전체 흐름")
                .feed(LoadTestConfig.bookingFeeder())
                .exec(session -> session
                        .set("performanceId", LoadTestConfig.performanceId())
                        .set("seatIdsJson", "[" + session.getLong("seatId") + "]"))
                .exec(joinQueue())
                .exitHereIfFailed()
                .exec(session -> session.set("admissionReady", false))
                // exitASAP=false: 한 번 돈 조회 결과는 끝까지 반영한다. 마지막 pause만큼 제한 시간을 넘길 수 있다.
                .asLongAsDuring(session -> !session.getBoolean("admissionReady"), queuePollTimeout, false).on(
                        exec(pollQueueState())
                                .exitHereIfFailed()
                                .exec(updateQueueState())
                                .doIf(session -> !session.getBoolean("admissionReady")).then(
                                        pause(session -> Duration.ofMillis(session.getLong("queuePollDelayMs")))
                                )
                )
                .exec(doIf(session -> !session.getBoolean("admissionReady")).then(
                        recordQueueTimeout()
                ))
                .exitHereIfFailed()
                .exec(enterQueue())
                .exitHereIfFailed()
                .exec(CoreBookingFlow.fetchSeatStatus(true))
                .exitHereIfFailed()
                .exec(CoreBookingFlow.selectSeat(true, false))
                .exitHereIfFailed()
                .exec(CoreBookingFlow.createOrder(true, false))
                .exitHereIfFailed()
                .exec(CoreBookingFlow.resolveOrderKey())
                .exec(doIf(Session::isFailed).then(
                        dummy("order key contract failure", 0).withSuccess(false)
                ))
                .exitHereIfFailed()
                .exec(session -> session.set("orderPending", false))
                .asLongAsDuring(session -> !session.getBoolean("orderPending"), ORDER_POLL_TIMEOUT, false).on(
                        exec(CoreBookingFlow.fetchOrder())
                                .exitHereIfFailed()
                                .exec(session -> session.set(
                                        "orderPending",
                                        "PENDING".equals(CoreBookingFlow.optionalString(session, "orderState"))
                                ))
                                .doIf(session -> !session.getBoolean("orderPending")).then(pause(ORDER_POLL_PAUSE))
                )
                .exec(doIf(session -> !session.getBoolean("orderPending")).then(
                        dummy("order state timeout", 0)
                                .withSuccess(false)
                                .withSessionUpdate(session -> session.markAsFailed())
                ))
                .exitHereIfFailed()
                .exec(recordSuccess());

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0)
                );
    }

    private ChainBuilder joinQueue() {
        return exec(http("queue join")
                .post(LoadTestConfig.queueBaseUrl() + "/api/v1/queue/performances/#{performanceId}/join")
                .headers(LoadTestConfig.authHeaders())
                .check(status().is(200))
                .check(jsonPath("$.queueToken").saveAs("queueToken"))
                .check(jsonPath("$.shardId").saveAs("shardId"))
                .check(jsonPath("$.localSeq").ofLong().saveAs("localSeq")));
    }

    private ChainBuilder pollQueueState() {
        return exec(session -> session.removeAll("servingSeq", "refreshAfterMs"))
                .exec(http("queue state")
                        .get(LoadTestConfig.queueBaseUrl()
                                + "/api/v1/queue/performances/#{performanceId}/state")
                        .check(status().is(200))
                        .check(jsonPath("$.serving['#{shardId}']").ofLong().optional().saveAs("servingSeq"))
                        .check(jsonPath("$.refreshAfterMs").ofLong().optional().saveAs("refreshAfterMs")));
    }

    private ChainBuilder updateQueueState() {
        return exec(session -> {
            Session updated = session;
            if (session.contains("servingSeq")) {
                final long servingSeq = session.getLong("servingSeq");
                final boolean retrograde = session.contains("lastServingSeq")
                        && servingSeq < session.getLong("lastServingSeq");
                if (!retrograde) {
                    updated = updated.set("lastServingSeq", servingSeq);
                    if (servingSeq >= session.getLong("localSeq")) {
                        updated = updated.set("admissionReady", true);
                    }
                }
            }
            return updated.set("queuePollDelayMs", CoreBookingFlow.queuePollDelayMs(session));
        });
    }

    private ChainBuilder enterQueue() {
        return exec(http("queue enter")
                .post(LoadTestConfig.queueBaseUrl() + "/api/v1/queue/performances/#{performanceId}/enter")
                .headers(LoadTestConfig.queueTokenHeaders())
                .check(status().is(200))
                .check(jsonPath("$.admissionToken").saveAs("admissionToken")));
    }

    private ChainBuilder recordQueueTimeout() {
        return exec(session -> {
            BookingResultRecorder.append(
                    Path.of(LoadTestConfig.resultFile()),
                    SCENARIO,
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId"),
                    session.getLong("seatId"),
                    null,
                    0,
                    "QUEUE_TIMEOUT"
            );
            return session.markAsFailed();
        });
    }

    private ChainBuilder recordSuccess() {
        return exec(session -> {
            BookingResultRecorder.append(
                    Path.of(LoadTestConfig.resultFile()),
                    SCENARIO,
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId"),
                    session.getLong("seatId"),
                    session.getString("orderKey"),
                    session.getInt("orderHttpStatus"),
                    "SUCCESS"
            );
            return session;
        });
    }
}
