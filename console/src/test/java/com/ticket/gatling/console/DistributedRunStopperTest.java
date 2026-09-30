package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributedRunStopperTest {
    private static final UUID RUN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void targetsOnlyProcessesTaggedWithTheConsoleRunId() {
        final String command = DistributedRunStopper.remoteStopCommand(RUN_ID);

        assertTrue(command.contains("[1]1111111-2222-3333-4444-555555555555"));
        assertTrue(command.contains("kill -TERM"));
        assertTrue(command.contains("kill -KILL"));
        assertTrue(command.contains("remote-stop-ok"));
        assertFalse(command.contains("pkill -f java"));
        assertFalse(command.contains("pkill -f gatling"));
    }
}
