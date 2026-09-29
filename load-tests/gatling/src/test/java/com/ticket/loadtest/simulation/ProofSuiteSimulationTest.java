package com.ticket.loadtest.simulation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProofSuiteSimulationTest {

    private static final Path SIMULATION_ROOT = Path.of("src/gatling/java/com/ticket/loadtest/simulation");
    private static final Path CONFIG = Path.of("src/gatling/java/com/ticket/loadtest/LoadTestConfig.java");

    @Test
    void usesTheSameRealBookingFlowForSmokeCapacitySpikeAndQueueProtection() throws IOException {
        final String flow = source(SIMULATION_ROOT.resolve("CoreBookingFlow.java"));

        assertFalse(flow.contains("/api/v1/members"));
        assertTrue(flow.contains("/performances/#{performanceId}/summary"));
        assertTrue(flow.contains("/performances/#{performanceId}/seats/status"));
        assertTrue(flow.contains("/performances/#{performanceId}/seats/#{seatId}/select"));
        assertTrue(flow.contains(".post(\"/api/v1/orders\")"));
        assertTrue(flow.contains("BookingResultRecorder.append("));
        assertTrue(flow.contains("pause(LoadTestConfig.bookingSeatThinkMin()"));
        assertTrue(flow.contains("pause(LoadTestConfig.bookingOrderThinkMin()"));
        assertTrue(flow.contains("shouldRefreshSeatStatus()"));
        assertTrue(flow.contains("shouldDropBeforeOrder()"));
        assertTrue(flow.contains("selectSeatAllowingConflict(includeAdmissionToken)"));

        assertTrue(source(SIMULATION_ROOT.resolve("SmokeSimulation.java"))
                .contains("CoreBookingFlow.successfulFlow(SCENARIO, true)"));
        assertTrue(source(SIMULATION_ROOT.resolve("CoreAdmissionCapacitySimulation.java"))
                .contains("CoreBookingFlow.successfulFlowWithoutAdmission(SCENARIO, true)"));
        assertTrue(source(SIMULATION_ROOT.resolve("CoreRealisticContentionSimulation.java"))
                .contains("CoreBookingFlow.realisticFlowWithoutAdmission(SCENARIO)"));
        assertTrue(source(SIMULATION_ROOT.resolve("CoreActiveUsersClosedSimulation.java"))
                .contains("CoreBookingFlow.realisticFlow(SCENARIO)"));
        assertTrue(source(SIMULATION_ROOT.resolve("CoreSpikeSimulation.java"))
                .contains("CoreBookingFlow.realisticFlow(SCENARIO)"));
        assertTrue(source(SIMULATION_ROOT.resolve("QueueProtectsCoreSimulation.java"))
                .contains("CoreBookingFlow.successfulFlow(SCENARIO, false)"));
    }

    @Test
    void hotSeatRequiresExactlyOneSelectionAndOrderWinner() throws IOException {
        final String source = source(SIMULATION_ROOT.resolve("HotSeatConcurrencySimulation.java"));

        assertTrue(source.contains(".rendezVous(users)"));
        assertTrue(source.contains("details(\"select won\").successfulRequests().count().is(1L)"));
        assertTrue(source.contains("details(\"order won\").successfulRequests().count().is(1L)"));
        // 나머지 사용자는 비즈니스 거절이거나 과부하(E6003)라서 거절 수를 users - 1로 고정하지 않는다.
        assertTrue(source.contains("global().failedRequests().count().is(0L)"));
        assertTrue(source.contains("dummy(\"select overloaded\", 0)"));
        assertTrue(source.contains("dummy(\"order overloaded\", 0)"));
        assertTrue(source.contains("CoreRejections.ofSelect(httpStatus, errorCode)"));
        assertTrue(source.contains("CoreRejections.ofOrder(httpStatus, errorCode)"));
        assertTrue(source.contains("final boolean orderWithoutSelect = won && !session.getBoolean(\"selectWon\")"));
    }

    @Test
    void coreSpikeUsesThirtySecondBaselinesAndFiveSecondRamps() throws IOException {
        final String config = source(CONFIG);
        final String simulation = source(SIMULATION_ROOT.resolve("CoreSpikeSimulation.java"));

        assertTrue(config.contains("CORE_SPIKE_BASELINE_SECONDS = 30"));
        assertTrue(config.contains("CORE_SPIKE_RAMP_SECONDS = 5"));
        assertTrue(config.contains("CORE_SPIKE_RECOVERY_SECONDS = 30"));
        assertTrue(config.contains("rampUsersPerSec(baselineUsersPerSecond).to(peakUsersPerSecond)"));
        assertTrue(simulation.contains("LoadTestConfig.coreSpikeInjection()"));
        assertTrue(simulation.contains("LoadTestConfig.coreSpikeExpectedUsers()"));
    }

    @Test
    void coreActiveUsersUsesAClosedModelWithRampAndSteadyState() throws IOException {
        final String config = source(CONFIG);
        final String simulation = source(SIMULATION_ROOT.resolve("CoreActiveUsersClosedSimulation.java"));

        assertTrue(config.contains("CORE_ACTIVE_USERS_RAMP_SECONDS = 30"));
        assertTrue(config.contains("rampConcurrentUsers(0).to(users())"));
        assertTrue(config.contains("constantConcurrentUsers(users())"));
        assertTrue(config.contains("rows < users()"));
        assertTrue(simulation.contains("injectClosed(LoadTestConfig.coreActiveUsersInjection())"));
        assertTrue(simulation.contains("scenario(\"04 Core 동시 사용자 한계\")"));
        assertTrue(simulation.contains("bookingFeeder(LoadTestConfig.bookingFeederRows())"));
        assertTrue(simulation.contains("CoreBookingFlow.realisticFlow(SCENARIO)"));
    }

    @Test
    void coreAdmissionCapacityKeepsTheFixedOpenModelContractAndCorrelationHeaders() throws IOException {
        final String simulation = source(SIMULATION_ROOT.resolve("CoreAdmissionCapacitySimulation.java"));
        final String flow = source(SIMULATION_ROOT.resolve("CoreBookingFlow.java"));
        final String config = source(CONFIG);
        final String runConfig = source(SIMULATION_ROOT.resolve("BookingRunConfigurationWriter.java"));

        assertTrue(simulation.contains("bookingFeeder(LoadTestConfig.expectedUsers())"));
        assertTrue(simulation.contains("successfulFlowWithoutAdmission(SCENARIO, true)"));
        assertTrue(simulation.contains("injectOpen(LoadTestConfig.coreAdmissionCapacityInjection())"));
        assertTrue(config.contains("CoreAdmissionCapacitySimulation requires -DinjectionMode=constant-users-per-sec"));
        assertTrue(flow.contains("ChainBuilder flow = recordCoreAdmission()"));
        assertTrue(flow.indexOf("recordCoreAdmission()") < flow.indexOf("fetchPerformanceSummary()"));
        assertTrue(flow.contains(".exec(fetchSeatStatus(includeAdmissionToken))"));
        assertTrue(flow.contains("exec(selectSeat(includeAdmissionToken))"));
        assertTrue(flow.contains("exec(createOrder(includeAdmissionToken))"));
        assertTrue(flow.contains("exec(fetchOrder())"));
        assertTrue(config.contains("\"X-Load-Test-Run-Id\", consoleRunId()"));
        assertTrue(config.contains("\"X-Load-Test-Scenario\", bookingScenario()"));
        assertTrue(config.contains("\"X-Load-Test-User-Id\", \"#{memberId}\""));
        assertTrue(runConfig.contains("\\\"feeder\\\""));
        assertTrue(runConfig.contains("LoadTestConfig.bookingFeederOffset()"));
    }

    @Test
    void orderCreateApiSelectsTheSeatFirstAndProducesTheOrderGetFeeder() throws IOException {
        final String source = source(SIMULATION_ROOT.resolve("CoreOrderCreateApiSimulation.java"));

        // Core는 본인이 선택 중인 좌석으로만 주문을 받는다.
        assertTrue(source.indexOf("/seats/#{seatId}/select") >= 0);
        assertTrue(source.indexOf("/seats/#{seatId}/select") < source.indexOf(".post(\"/api/v1/orders\")"));
        assertTrue(source.contains("OrderLookupFeeder.initialize("));
        assertTrue(source.contains("header(\"X-Order-Key\").saveAs(\"orderKey\")"));
        assertTrue(source.contains("OrderLookupFeeder.append("));
    }

    @Test
    void orderGetApiCreatesJwtInMemoryFromTheSafeOrderFeeder() throws IOException {
        final String source = source(SIMULATION_ROOT.resolve("CoreOrderGetApiSimulation.java"));

        assertTrue(source.contains("orderLookupFeeder(LoadTestConfig.expectedUsers())"));
        assertTrue(source.contains("syntheticAccessTokenForMember(session.getLong(\"memberId\"))"));
        assertTrue(source.contains("LoadTestConfig.authAndCorrelationHeaders()"));
    }

    @Test
    void queueProtectionSeparatesExternalArrivalsFromCoreAdmissions() throws IOException {
        final String source = source(SIMULATION_ROOT.resolve("QueueProtectsCoreSimulation.java"));

        assertTrue(source.contains("dummy(\"external arrival\", 0)"));

        assertTrue(source.contains("jsonPath(\"$.serving['#{shardId}']\")"));
        assertTrue(source.contains("jsonPath(\"$.admissionToken\").optional().saveAs(\"admissionToken\")"));
        assertTrue(source.contains("CoreBookingFlow.successfulFlow(SCENARIO, false)"));
        assertTrue(source.indexOf("dummy(\"external arrival\", 0)")
                < source.indexOf("CoreBookingFlow.successfulFlow(SCENARIO, false)"));
    }

    private static String source(final Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
