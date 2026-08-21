package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEnvironmentInputLocalTargetTest {

    @Test
    void disablesCaptureForLocalCoreTarget() {
        final RunEnvironmentInput input = RunEnvironmentInput.automatic(
                SimulationType.CORE_PERFORMANCE_SUMMARY_API,
                "",
                "http://localhost:8080",
                ""
        );

        assertFalse(input.captureEnabled());
    }

    @Test
    void disablesCaptureForLoopbackCoreTarget() {
        final RunEnvironmentInput input = RunEnvironmentInput.automatic(
                SimulationType.CORE_PERFORMANCE_SUMMARY_API,
                "",
                "http://127.0.0.1:8080",
                ""
        );

        assertFalse(input.captureEnabled());
    }

    @Test
    void keepsCaptureForRemoteCoreTarget() {
        final RunEnvironmentInput input = RunEnvironmentInput.automatic(
                SimulationType.CORE_PERFORMANCE_SUMMARY_API,
                "",
                "https://oneticket.site",
                ""
        );

        assertTrue(input.captureEnabled());
    }
}
