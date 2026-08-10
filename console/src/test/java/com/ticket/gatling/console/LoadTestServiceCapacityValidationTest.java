package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadTestServiceCapacityValidationTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsOffsetWindowShortageBeforeStartingTheGatlingProcess() throws IOException {
        final Path feeder = tempDir.resolve("capacity.csv");
        Files.writeString(feeder, """
                memberId,accessToken,seatId,admissionToken
                1,token-1,101,
                2,token-2,102,
                3,token-3,103,
                """, StandardCharsets.UTF_8);
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.ofEntries(
                Map.entry("ticketProjectPath", List.of(tempDir.toString())),
                Map.entry("simulation", List.of("core-admission-capacity")),
                Map.entry("coreBaseUrl", List.of("https://core.example.com")),
                Map.entry("injectionMode", List.of("constant-users-per-sec")),
                Map.entry("usersPerSecond", List.of("2")),
                Map.entry("durationSeconds", List.of("1")),
                Map.entry("bookingFeederFile", List.of(feeder.toString())),
                Map.entry("bookingFeederOffset", List.of("2")),
                Map.entry("operationalConfirmation", List.of("on"))
        ));

        final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new LoadTestService(new ReportRegistry()).start(request)
        );

        assertTrue(failure.getMessage().contains("offset=2"));
        assertTrue(failure.getMessage().contains("required=4"));
        assertTrue(failure.getMessage().contains("actual=3"));
    }
}
