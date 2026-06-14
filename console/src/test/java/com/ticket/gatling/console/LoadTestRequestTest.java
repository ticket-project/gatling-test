package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoadTestRequestTest {

    @Test
    void readsAdmissionTokenOptionsFromForm() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "admissionTokenMode", List.of("tokens"),
                "admissionTokens", List.of("adm-1,adm-2"),
                "admissionTokenIssuer", List.of("queue"),
                "admissionTokenAudience", List.of("api"),
                "admissionTokenSecret", List.of("secret-0123456789abcdef0123456789"),
                "admissionTokenTtlSeconds", List.of("600")
        ));

        assertEquals("tokens", request.admissionTokenMode());
        assertEquals("adm-1,adm-2", request.admissionTokens());
        assertEquals("queue", request.admissionTokenIssuer());
        assertEquals("api", request.admissionTokenAudience());
        assertEquals("secret-0123456789abcdef0123456789", request.admissionTokenSecret());
        assertEquals(600, request.admissionTokenTtlSeconds());
    }

    @Test
    void estimatesVirtualUsersForConstantRateInjection() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "injectionMode", List.of("constant-users-per-sec"),
                "users", List.of("10"),
                "usersPerSecond", List.of("7.5"),
                "durationSeconds", List.of("60")
        ));

        assertEquals(450, request.estimatedVirtualUsers());
    }

    @Test
    void estimatesVirtualUsersForRampRateInjection() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "injectionMode", List.of("ramp-users-per-sec"),
                "usersPerSecond", List.of("5"),
                "targetUsersPerSecond", List.of("15"),
                "durationSeconds", List.of("60")
        ));

        assertEquals(600, request.estimatedVirtualUsers());
    }
}
