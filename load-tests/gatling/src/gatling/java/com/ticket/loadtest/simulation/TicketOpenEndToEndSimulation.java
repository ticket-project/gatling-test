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
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.dummy;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class TicketOpenEndToEndSimulation extends Simulation {

    private static final String SCENARIO = "TICKET_OPEN_END_TO_END";
    private static final Duration ORDER_POLL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ORDER_POLL_PAUSE = Duration.ofMillis(200);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(LoadTestConfig.coreBaseUrl())
            .shareConnections()
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    public TicketOpenEndToEndSimulation() {
        final Duration queuePollTimeout = Duration.ofSeconds(LoadTestConfig.pollingTimeoutSeconds());
        final ScenarioBuilder scenario = scenario("예매 오픈 전체 흐름")
                .feed(LoadTestConfig.bookingFeeder())
                .exec(session -> session
                        .set("performanceId", LoadTestConfig.performanceId())
                        .set("seatIdsJson", "[" + session.getLong("seatId") + "]"))
                .exec(joinQueue())
                .exitHereIfFailed()
                .exec(session -> session
                        .set("admissionReady", false)
                        .set("queueDeadlineNanos", deadlineAfter(queuePollTimeout)))
                .asLongAs(session -> !session.getBoolean("admissionReady")
                        && remainingNanos(session, "queueDeadlineNanos") > 0).on(
                        exec(pollQueueState())
                                .exitHereIfFailed()
                                .exec(updateQueueState())
                                .doIf(session -> !session.getBoolean("admissionReady")
                                        && remainingNanos(session, "queueDeadlineNanos") > 0).then(
                                        pause(session -> clampedPause(
                                                Duration.ofMillis(session.getLong("queuePollDelayMs")),
                                                remainingNanos(session, "queueDeadlineNanos")
                                        ))
                                )
                )
                .exec(doIf(session -> !session.getBoolean("admissionReady")).then(
                        recordQueueTimeout()
                ))
                .exitHereIfFailed()
                .exec(enterQueue())
                .exitHereIfFailed()
                .exec(fetchSeatStatus())
                .exitHereIfFailed()
                .exec(selectSeat())
                .exitHereIfFailed()
                .exec(createOrder())
                .exitHereIfFailed()
                .exec(resolveOrderKey())
                .exec(doIf(session -> session.getBoolean("orderKeyContractFailure")).then(
                        dummy("order key contract failure", 0)
                                .withSuccess(false)
                                .withSessionUpdate(session -> session.markAsFailed())
                ))
                .exitHereIfFailed()
                .exec(session -> session
                        .set("orderPending", false)
                        .set("orderDeadlineNanos", deadlineAfter(ORDER_POLL_TIMEOUT)))
                .asLongAs(session -> !session.getBoolean("orderPending")
                        && remainingNanos(session, "orderDeadlineNanos") > 0).on(
                        exec(fetchOrder())
                                .exitHereIfFailed()
                                .exec(session -> session.set(
                                        "orderPending",
                                        "PENDING".equals(session.getString("orderStatus"))
                                ))
                                .doIf(session -> !session.getBoolean("orderPending")
                                        && remainingNanos(session, "orderDeadlineNanos") > 0).then(
                                        pause(session -> clampedPause(
                                                ORDER_POLL_PAUSE,
                                                remainingNanos(session, "orderDeadlineNanos")
                                        ))
                                )
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
                        global().failedRequests().percent().lt(1.0),
                        details("queue join").responseTime().percentile(99.0).lt(2000),
                        details("queue state").responseTime().percentile(99.0).lt(2000),
                        details("queue enter").responseTime().percentile(99.0).lt(2000),
                        details("seat status").responseTime().percentile(99.0).lt(3000),
                        details("select seat").responseTime().percentile(99.0).lt(3000),
                        details("create order").responseTime().percentile(99.0).lt(3000),
                        details("get order").responseTime().percentile(99.0).lt(3000)
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
            return updated.set("queuePollDelayMs", queuePollDelayMs(session));
        });
    }

    private ChainBuilder enterQueue() {
        return exec(http("queue enter")
                .post(LoadTestConfig.queueBaseUrl() + "/api/v1/queue/performances/#{performanceId}/enter")
                .headers(LoadTestConfig.queueTokenHeaders())
                .check(status().is(200))
                .check(jsonPath("$.data.admissionToken").saveAs("admissionToken")));
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

    private ChainBuilder fetchSeatStatus() {
        return exec(http("seat status")
                .get("/api/v1/performances/#{performanceId}/seats/status")
                .headers(LoadTestConfig.authAndAdmissionHeaders())
                .check(status().is(200)));
    }

    private ChainBuilder selectSeat() {
        return exec(http("select seat")
                .post("/api/v1/performances/#{performanceId}/seats/#{seatId}/select")
                .headers(LoadTestConfig.authAndAdmissionHeaders())
                .check(status().is(200)));
    }

    private ChainBuilder createOrder() {
        return exec(http("create order")
                .post("/api/v1/orders")
                .headers(LoadTestConfig.authAndAdmissionHeaders())
                .body(StringBody("""
                        {
                          "performanceId": #{performanceId},
                          "seatIds": #{seatIdsJson}
                        }
                        """))
                .check(status().is(201))
                .check(header("X-Order-Key").optional().saveAs("orderKeyHeader"))
                .check(jsonPath("$.data.orderKey").optional().saveAs("orderKeyBody")));
    }

    private ChainBuilder resolveOrderKey() {
        return exec(session -> {
            final String headerOrderKey = optionalString(session, "orderKeyHeader");
            final String bodyOrderKey = optionalString(session, "orderKeyBody");
            if (headerOrderKey.isBlank() && bodyOrderKey.isBlank()) {
                return session.set("orderKeyContractFailure", true);
            }
            if (!headerOrderKey.isBlank() && !bodyOrderKey.isBlank() && !headerOrderKey.equals(bodyOrderKey)) {
                return session.set("orderKeyContractFailure", true);
            }
            return session
                    .set("orderKeyContractFailure", false)
                    .set("orderKey", headerOrderKey.isBlank() ? bodyOrderKey : headerOrderKey);
        });
    }

    private ChainBuilder fetchOrder() {
        return exec(http("get order")
                .get("/api/v1/orders/#{orderKey}/status")
                .headers(LoadTestConfig.authAndAdmissionHeaders())
                .check(status().saveAs("orderHttpStatus"))
                .check(status().is(200))
                .check(jsonPath("$.data.status").saveAs("orderStatus")));
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

    private static long queuePollDelayMs(final Session session) {
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

    private static long deadlineAfter(final Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static long remainingNanos(final Session session, final String deadlineKey) {
        return Math.max(0L, session.getLong(deadlineKey) - System.nanoTime());
    }

    private static Duration clampedPause(final Duration requestedPause, final long remainingNanos) {
        return Duration.ofNanos(Math.min(requestedPause.toNanos(), remainingNanos));
    }

    private static String optionalString(final Session session, final String key) {
        return session.contains(key) ? session.getString(key) : "";
    }
}
