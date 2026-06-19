package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportDirectoryNameFormatterTest {

    @Test
    void formatsCdnReportDirectoryNameWithDetailedPollingConditions() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("cdn-public-state"),
                "baseUrl", List.of("https://queue.oneticket.site"),
                "injectionMode", List.of("constant-users-per-sec"),
                "usersPerSecond", List.of("400"),
                "durationSeconds", List.of("50"),
                "statusPolls", List.of("10"),
                "statusPollPauseSeconds", List.of("5")
        ));

        assertEquals(
                "cdn(queue.oneticket.site) 20만(50초간20000명이 10번씩 5초 주기로)",
                ReportDirectoryNameFormatter.format(request)
        );
    }

    @Test
    void formatsExplicitUrlPortWithoutWindowsInvalidCharacters() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("ticket-server-capacity"),
                "baseUrl", List.of("http://localhost:8080/api"),
                "users", List.of("1250"),
                "durationSeconds", List.of("30")
        ));

        assertEquals(
                "ticket-server(localhost.8080) 1,250(30초간1250명)",
                ReportDirectoryNameFormatter.format(request)
        );
    }
}
