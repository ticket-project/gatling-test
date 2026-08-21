package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleEnvironmentsApiTest {

    @Test
    void bundledEnvironmentsFileDeclaresLocalAndProd() {
        final List<TargetEnvironment> targets =
                TargetEnvironmentCatalog.load(Path.of(TargetEnvironmentCatalog.DEFAULT_FILE_NAME));

        assertEquals(2, targets.size());
        assertEquals("local", targets.get(0).key());
        assertEquals("http://localhost:8080", targets.get(0).coreBaseUrl());
        assertEquals("prod", targets.get(1).key());
        assertEquals("https://oneticket.site", targets.get(1).coreBaseUrl());
    }

    @Test
    void serializesTargetsAsJsonArray() {
        final String json = ConsoleServer.environmentsJson(List.of(
                new TargetEnvironment("local", "로컬 (H2)", "http://localhost:8080", "http://localhost:8081")
        ));

        assertTrue(json.startsWith("["));
        assertTrue(json.contains("\"key\":\"local\""));
        assertTrue(json.contains("\"coreBaseUrl\":\"http://localhost:8080\""));
        assertTrue(json.contains("\"queueBaseUrl\":\"http://localhost:8081\""));
    }

    @Test
    void serializesEmptyListAsEmptyJsonArray() {
        assertEquals("[]", ConsoleServer.environmentsJson(List.of()));
    }
}
