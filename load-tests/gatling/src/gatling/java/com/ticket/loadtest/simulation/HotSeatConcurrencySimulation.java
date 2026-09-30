package com.ticket.loadtest.simulation;

import com.ticket.loadtest.BookingResultRecorder;
import com.ticket.loadtest.CoreRejections;
import com.ticket.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.nio.file.Path;

import static com.ticket.loadtest.simulation.CoreBookingFlow.canContinue;
import static com.ticket.loadtest.simulation.CoreBookingFlow.optionalInt;
import static com.ticket.loadtest.simulation.CoreBookingFlow.optionalString;
import static com.ticket.loadtest.simulation.CoreBookingFlow.overloaded;
import static com.ticket.loadtest.simulation.CoreBookingFlow.terminalFailure;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.dummy;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;

public class HotSeatConcurrencySimulation extends BookingProofSimulation {

    private static final String SCENARIO = "HOT_SEAT_CONCURRENCY";

    public HotSeatConcurrencySimulation() {
        super(SCENARIO);
        final int users = LoadTestConfig.users();
        if (users < 2) {
            throw new IllegalArgumentException("-Dusers must be at least 2 for hot-seat contention");
        }

        final HttpProtocolBuilder httpProtocol = Protocols.json(LoadTestConfig.coreBaseUrl());

        final ScenarioBuilder scenario = scenario("02 인기 좌석 동시 경합")
                .feed(LoadTestConfig.bookingFeeder(users))
                .exec(CoreBookingFlow.initializeSession(SCENARIO))
                .rendezVous(users)
                .exec(CoreBookingFlow.recordCoreAdmission())
                .exec(CoreBookingFlow.selectSeat(true, true))
                .exec(classifySelect())
                .exec(doIf(session -> session.getBoolean("selectWon")).then(
                        dummy("select won", 0)
                ))
                .exec(doIf(session -> session.getBoolean("selectRejected")).then(
                        dummy("select business rejected", 0)
                ))
                .exec(doIf(session -> session.getBoolean("selectOverloaded")).then(
                        dummy("select overloaded", 0)
                ))
                .exec(doIf(session -> session.getBoolean("selectTechnicalFailure")).then(
                        dummy("select technical failure", 0)
                                .withSuccess(false)
                                .withSessionUpdate(Session::markAsFailed)
                ))
                .exec(doIf(CoreBookingFlow::canContinue).then(
                        exec(CoreBookingFlow.createOrder(true, true))
                                .exec(classifyOrder())
                ))
                .exec(doIf(session -> session.getBoolean("orderTechnicalFailure")).then(
                        dummy("order technical failure", 0)
                                .withSuccess(false)
                                .withSessionUpdate(Session::markAsFailed)
                ))
                .exec(doIf(session -> session.getBoolean("orderWithoutSelect")).then(
                        dummy("order without select", 0)
                                .withSuccess(false)
                                .withSessionUpdate(Session::markAsFailed)
                ))
                .exec(doIf(session -> session.getBoolean("orderBusinessRejected")).then(
                        dummy("order business rejected", 0)
                ))
                .exec(doIf(session -> session.getBoolean("orderOverloaded")).then(
                        dummy("order overloaded", 0)
                ))
                .exec(doIf(session -> session.getBoolean("orderWon") && canContinue(session)).then(
                        exec(CoreBookingFlow.resolveOrderKey())
                                .exec(doIf(CoreBookingFlow::canContinue).then(markSuccess()))
                ))
                .exec(doIf(session -> session.getBoolean("orderWon") && canContinue(session)).then(
                        dummy("order won", 0)
                ))
                .exec(recordTerminal());

        setUp(scenario.injectOpen(LoadTestConfig.injection()))
                .protocols(httpProtocol)
                .assertions(
                        // 나머지 사용자는 비즈니스 거절이거나 과부하(E6003)다. 기술 실패는 failedRequests가 잡는다.
                        global().failedRequests().count().is(0L),
                        details("select won").successfulRequests().count().is(1L),
                        details("order won").successfulRequests().count().is(1L)
                );
    }

    private ChainBuilder classifySelect() {
        return exec(session -> {
            final int httpStatus = optionalInt(session, "selectHttpStatus");
            final String errorCode = optionalString(session, "selectErrorCode");
            final boolean won = httpStatus == 200;
            final CoreRejections.Kind rejection = CoreRejections.ofSelect(httpStatus, errorCode);
            final boolean rejected = !won && rejection == CoreRejections.Kind.BUSINESS_REJECTED;
            final boolean overloaded = !won && rejection == CoreRejections.Kind.OVERLOADED;
            Session updated = session
                    .set("selectWon", won)
                    .set("selectRejected", rejected)
                    .set("selectOverloaded", overloaded)
                    .set("selectTechnicalFailure", !won && !rejected && !overloaded)
                    .set("orderWon", false)
                    .set("orderBusinessRejected", false)
                    .set("orderOverloaded", false)
                    .set("orderWithoutSelect", false)
                    .set("orderTechnicalFailure", false);
            if (overloaded) {
                updated = overloaded(updated, "SELECT_SEAT", httpStatus, errorCode);
            } else if (!won && !rejected) {
                updated = terminalFailure(updated, technicalResult("SELECT_SEAT", httpStatus),
                        "SELECT_SEAT", httpStatus);
            }
            return updated;
        });
    }

    private ChainBuilder classifyOrder() {
        return exec(session -> {
            final int httpStatus = optionalInt(session, "createOrderHttpStatus");
            final String errorCode = optionalString(session, "orderErrorCode");
            final boolean won = httpStatus == 201;
            final CoreRejections.Kind rejection = CoreRejections.ofOrder(httpStatus, errorCode);
            final boolean businessRejected = !won && rejection == CoreRejections.Kind.BUSINESS_REJECTED;
            final boolean overloaded = !won && rejection == CoreRejections.Kind.OVERLOADED;
            final boolean orderWithoutSelect = won && !session.getBoolean("selectWon");
            final boolean technicalFailure = !won && !businessRejected && !overloaded;
            Session updated = session
                    .set("orderWon", won)
                    .set("orderBusinessRejected", businessRejected)
                    .set("orderOverloaded", overloaded)
                    .set("orderWithoutSelect", orderWithoutSelect)
                    .set("orderTechnicalFailure", technicalFailure);
            if (orderWithoutSelect) {
                return terminalFailure(updated, "INVARIANT_ORDER_WITHOUT_SELECT", "CREATE_ORDER", httpStatus);
            }
            if (technicalFailure) {
                return terminalFailure(updated, technicalResult("CREATE_ORDER", httpStatus),
                        "CREATE_ORDER", httpStatus);
            }
            if (overloaded) {
                return overloaded(updated, "CREATE_ORDER", httpStatus, errorCode);
            }
            if (businessRejected) {
                return updated
                        .set("terminalResult", CoreRejections.resultName(CoreRejections.Kind.BUSINESS_REJECTED, errorCode))
                        .set("terminalHttpStatus", httpStatus)
                        .set("lastStep", "CREATE_ORDER");
            }
            return updated;
        });
    }

    private ChainBuilder markSuccess() {
        return exec(session -> session
                .set("terminalResult", "SUCCESS")
                .set("terminalHttpStatus", optionalInt(session, "createOrderHttpStatus"))
                .set("lastStep", "COMPLETED"));
    }

    private ChainBuilder recordTerminal() {
        return exec(session -> {
            final String result = optionalString(session, "terminalResult");
            final String normalizedResult = result.isBlank() ? "INVARIANT_MISSING_TERMINAL_RESULT" : result;
            final Session updated = result.isBlank() ? session.markAsFailed() : session;
            BookingResultRecorder.append(
                    Path.of(LoadTestConfig.resultFile()),
                    SCENARIO,
                    LoadTestConfig.nodeIndex(),
                    updated.getLong("memberId"),
                    updated.getLong("seatId"),
                    optionalString(updated, "orderKey"),
                    optionalInt(updated, "terminalHttpStatus"),
                    normalizedResult,
                    optionalString(updated, "lastStep"),
                    optionalString(updated, "flowStartedAt"),
                    optionalString(updated, "coreAdmittedAt")
            );
            return updated;
        });
    }

    private static String technicalResult(final String step, final int httpStatus) {
        return httpStatus == 0
                ? "TECHNICAL_" + step + "_NO_RESPONSE"
                : "TECHNICAL_" + step + "_HTTP_" + httpStatus;
    }
}
