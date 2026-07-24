package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void distributedScriptsTagRemoteProcessesWithTheConsoleRunId() throws IOException {
        final String cdn = source("run-distributed-gatling-cdn.ps1");
        final String join = source("run-distributed-gatling-join.ps1");
        final String legacy = source("run-distributed-gatling-legacy.ps1");
        final String booking = source("run-distributed-booking.ps1");

        assertTrue(cdn.contains("[string]$ConsoleRunId"));
        assertTrue(cdn.contains("-DconsoleRunId=$ConsoleRunId"));
        assertTrue(join.contains("ConsoleRunId = $ConsoleRunId"));
        assertTrue(legacy.contains("ConsoleRunId = $ConsoleRunId"));
        assertTrue(booking.contains("[string]$ConsoleRunId"));
        assertTrue(booking.contains("-DconsoleRunId=$ConsoleRunId"));
    }

    private static String source(final String name) throws IOException {
        return Files.readString(Path.of("..", name), StandardCharsets.UTF_8);
    }
}
