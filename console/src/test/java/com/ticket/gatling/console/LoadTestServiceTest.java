//package com.ticket.gatling.console;
//
//import org.junit.jupiter.api.Test;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
//import static org.junit.jupiter.api.Assertions.assertThrows;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//
//import java.util.List;
//import java.util.Map;
//
//class LoadTestServiceTest {
//
//    @Test
//    void rejectsAutomaticLoginWhenSeedMembersAreNotEnough() {
//        final LoadTestService service = new LoadTestService(new ReportRegistry());
//        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
//                "users", List.of("10000"),
//                "accessTokenMode", List.of("login"),
//                "loginStartIndex", List.of("1"),
//                "seedMemberCount", List.of("100")
//        ));
//
//        final IllegalArgumentException exception = assertThrows(
//                IllegalArgumentException.class,
//                () -> service.start(request)
//        );
//
//        assertTrue(exception.getMessage().contains("loadtest1 ~ loadtest10000"));
//        assertTrue(exception.getMessage().contains("Seed member count: 100"));
//    }
//
//    @Test
//    void allowsSyntheticJwtModeWithoutSeedMemberCountCheck() {
//        final LoadTestService service = new LoadTestService(new ReportRegistry());
//        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
//                "users", List.of("10000"),
//                "accessTokenMode", List.of("synthetic-jwt"),
//                "seedMemberCount", List.of("100")
//        ));
//
//        assertDoesNotThrow(() -> service.start(request));
//    }
//
//    @Test
//    void allowsSyntheticJwtModeWithIgnoredFormSecret() {
//        final LoadTestService service = new LoadTestService(new ReportRegistry());
//        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
//                "accessTokenMode", List.of("synthetic-jwt"),
//                "jwtSecret", List.of("short")
//        ));
//
//        assertEquals("0123456789abcdef0123456789abcdef", request.jwtSecret());
//        assertDoesNotThrow(() -> service.start(request));
//    }
//
//    @Test
//    void rejectsTokenModeWithoutAccessTokens() {
//        final LoadTestService service = new LoadTestService(new ReportRegistry());
//        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
//                "accessTokenMode", List.of("tokens")
//        ));
//
//        final IllegalArgumentException exception = assertThrows(
//                IllegalArgumentException.class,
//                () -> service.start(request)
//        );
//
//        assertTrue(exception.getMessage().contains("Access Token"));
//    }
//}
