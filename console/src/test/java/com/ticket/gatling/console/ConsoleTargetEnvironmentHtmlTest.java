package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleTargetEnvironmentHtmlTest {

    private String html() throws IOException {
        return Files.readString(
                Path.of("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
    }

    @Test
    void rendersTargetEnvironmentSelect() throws IOException {
        final String html = html();

        assertTrue(html.contains("id=\"targetEnvironment\""));
        assertTrue(html.contains("value=\"__custom__\""));
    }

    @Test
    void loadsEnvironmentsFromApi() throws IOException {
        assertTrue(html().contains("/api/environments"));
    }

    @Test
    void proofDefaultsNoLongerPinCoreBaseUrl() throws IOException {
        final String html = html();
        final int start = html.indexOf("const proofSimulationDefaults");
        final int end = html.indexOf("const coreApiSimulationKeys");

        assertTrue(start > 0 && end > start);
        assertFalse(html.substring(start, end).contains("coreBaseUrl"));
        assertFalse(html.substring(start, end).contains("queueBaseUrl"));
    }

    @Test
    void localCoreTargetIsExemptFromOperationalConfirmation() throws IOException {
        assertTrue(html().contains(
                "if (!isLocalhostUrl(coreBaseUrl) && body.get('operationalConfirmation') !== 'on')"));
    }

    @Test
    void localhostCoreUrlIsRejectedOnlyForDistributedExecution() throws IOException {
        final String html = html();

        assertTrue(html.contains("if (distributed && isLocalhostUrl(coreBaseUrl))"));
        assertFalse(html.contains("Booking 운영 부하 테스트의 Ticket/Core URL은 localhost를 사용할 수 없습니다."));
    }

    @Test
    void declaresEachTargetInputReferenceExactlyOnce() throws IOException {
        final String html = html();

        // 중복 const 선언은 스크립트 전체를 죽여서 화면이 빈 채로 뜬다.
        assertEquals(1, countOccurrences(html, "const coreBaseUrlInput ="));
        assertEquals(1, countOccurrences(html, "const queueBaseUrlInput ="));
        assertEquals(1, countOccurrences(html, "const targetEnvironmentSelect ="));
    }

    @Test
    void previewDoesNotFallBackToProductionUrls() throws IOException {
        final String html = html();

        assertFalse(html.contains("coreBaseUrlInput?.value || 'https://oneticket.site'"));
        assertFalse(html.contains("queueBaseUrlInput?.value || 'https://queue.oneticket.site'"));
    }

    private int countOccurrences(final String text, final String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }

    @Test
    void memberIdFileIsHiddenForLocalCoreTarget() throws IOException {
        assertTrue(html().contains(
                "existingMemberIdSimulationKeys.has(selected?.key) && !coreTargetIsLocal()"));
    }
}
