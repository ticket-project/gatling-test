package com.ticket.loadtest.simulation;

import com.ticket.loadtest.BookingResultRecorder;
import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.nio.file.Path;
import java.time.Duration;

import static com.ticket.loadtest.simulation.CoreBookingFlow.canContinue;
import static com.ticket.loadtest.simulation.CoreBookingFlow.optionalInt;
import static com.ticket.loadtest.simulation.CoreBookingFlow.optionalString;
import static com.ticket.loadtest.simulation.CoreBookingFlow.terminalFailure;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.dummy;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class QueueProtectsCoreSimulation extends BookingProofSimulation {

    private static final String SCENARIO = "QUEUE_PROTECTS_CORE";

    public QueueProtectsCoreSimulation() {
        super(SCENARIO);
        final int expectedUsers = LoadTestConfig.expectedUsers();
        final Duration queuePollTimeout = Duration.ofSeconds(LoadTestConfig.pollingTimeoutSeconds());
        final HttpProtocolBuilder httpProtocol = Protocols.json(LoadTestConfig.coreBaseUrl());

        final ScenarioBuilder scenario = scenario("06 Queue의 Core 보호")
                .feed(LoadTestConfig.bookingFeeder(expectedUsers))
                .exec(CoreBookingFlow.initializeSession(SCENARIO))
                .exec(dummy("external arrival", 0))
                .exec(joinQueue())
                .exec(captureQueueStatus("QUEUE_JOIN", "queueJoinHttpStatus", 200))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(session -> session.set("admissionReady", false))
                                .asLongAsDuring(session -> canContinue(session)
                                        && !session.getBoolean("admissionReady"), queuePollTimeout, false).on(
                                        exec(pollQueueState())
                                                .exec(captureQueueStatus("QUEUE_STATE", "queueStateHttpStatus", 200))
                                                .exec(doIf(CoreBookingFlow::canContinue).then(
                                                        updateQueueState()
                                                ))
                                                .doIf(session -> canContinue(session)
                                                        && !session.getBoolean("admissionReady")).then(
                                                        pause(session -> Duration.ofMillis(session.getLong("queuePollDelayMs")))
                                                )
                                )
                ))
                .exec(doIf(session -> canContinue(session) && !session.getBoolean("admissionReady")).then(
                        markQueueTimeout()
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(enterQueue())
                                .exec(captureQueueStatus("QUEUE_ENTER", "queueEnterHttpStatus", 200))
                                .exec(doIf(CoreBookingFlow::canContinue).then(
                                        validateAdmissionToken()
                                ))
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        CoreBookingFlow.successfulFlow(SCENARIO, false)
                ))
                .exec(doIf(session -> !canContinue(session)).then(
                        recordQueueTerminal()
                ));

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        Protocols.technicalFailuresBelowThreshold(),
                        details("external arrival").successfulRequests().count().is((long) expectedUsers)
                );
    }

    private ChainBuilder joinQueue() {
        return exec(session -> session
                .removeAll("queueJoinHttpStatus", "queueToken", "shardId", "localSeq")
                .set("lastStep", "QUEUE_JOIN"))
                .exec(http("queue join")
                        .post(LoadTestConfig.queueBaseUrl() + "/api/v1/queue/performances/#{performanceId}/join")
                        .headers(LoadTestConfig.authHeaders())
                        .check(status().saveAs("queueJoinHttpStatus"))
                        .check(status().is(200))
                        .check(jsonPath("$.queueToken").optional().saveAs("queueToken"))
                        .check(jsonPath("$.shardId").ofInt().optional().saveAs("shardId"))
                        .check(jsonPath("$.localSeq").ofLong().optional().saveAs("localSeq")));
    }

    private ChainBuilder pollQueueState() {
        return exec(session -> session
                .removeAll("queueStateHttpStatus", "servingSeq", "refreshAfterMs")
                .set("lastStep", "QUEUE_STATE"))
                .exec(http("queue state")
                        .get(LoadTestConfig.queueBaseUrl()
                                + "/api/v1/queue/performances/#{performanceId}/state")
                        .check(status().saveAs("queueStateHttpStatus"))
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
                if (retrograde) {
                    return terminalFailure(session, "QUEUE_STATE_RETROGRADE", "QUEUE_STATE",
                            optionalInt(session, "queueStateHttpStatus"));
                }
                updated = updated.set("lastServingSeq", servingSeq);
                if (servingSeq >= session.getLong("localSeq")) {
                    updated = updated.set("admissionReady", true);
                }
            }
            return updated.set("queuePollDelayMs", CoreBookingFlow.queuePollDelayMs(session));
        });
    }

    private ChainBuilder enterQueue() {
        return exec(session -> session
                .removeAll("queueEnterHttpStatus", "admissionToken")
                .set("lastStep", "QUEUE_ENTER"))
                .exec(http("queue enter")
                        .post(LoadTestConfig.queueBaseUrl() + "/api/v1/queue/performances/#{performanceId}/enter")
                        .headers(LoadTestConfig.queueTokenHeaders())
                        .check(status().saveAs("queueEnterHttpStatus"))
                        .check(status().is(200))
                        .check(jsonPath("$.admissionToken").optional().saveAs("admissionToken")));
    }

    private ChainBuilder validateAdmissionToken() {
        return exec(session -> optionalString(session, "admissionToken").isBlank()
                ? terminalFailure(session, "QUEUE_ADMISSION_TOKEN_MISSING", "QUEUE_ENTER",
                        optionalInt(session, "queueEnterHttpStatus"))
                : session);
    }

    private ChainBuilder captureQueueStatus(
            final String step,
            final String statusName,
            final int expectedStatus
    ) {
        return exec(session -> {
            if (!session.contains(statusName)) {
                return terminalFailure(session, "TECHNICAL_" + step + "_NO_RESPONSE", step, 0);
            }
            final int actualStatus = session.getInt(statusName);
            if (actualStatus != expectedStatus) {
                return terminalFailure(session, "TECHNICAL_" + step + "_HTTP_" + actualStatus,
                        step, actualStatus);
            }
            if ("QUEUE_JOIN".equals(step)
                    && (!session.contains("queueToken") || !session.contains("shardId") || !session.contains("localSeq"))) {
                return terminalFailure(session, "QUEUE_JOIN_CONTRACT_FAILURE", step, actualStatus);
            }
            return session;
        });
    }

    private ChainBuilder markQueueTimeout() {
        return exec(session -> terminalFailure(session, "QUEUE_TIMEOUT", "QUEUE_WAIT", 0));
    }

    private ChainBuilder recordQueueTerminal() {
        return exec(session -> {
            BookingResultRecorder.append(
                    Path.of(LoadTestConfig.resultFile()),
                    SCENARIO,
                    LoadTestConfig.nodeIndex(),
                    session.getLong("memberId"),
                    session.getLong("seatId"),
                    optionalString(session, "orderKey"),
                    optionalInt(session, "terminalHttpStatus"),
                    optionalString(session, "terminalResult"),
                    optionalString(session, "lastStep"),
                    optionalString(session, "flowStartedAt"),
                    optionalString(session, "coreAdmittedAt"),
                    "",
                    0
            );
            return session;
        });
    }
}
