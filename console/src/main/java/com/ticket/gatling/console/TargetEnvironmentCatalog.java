package com.ticket.gatling.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 콘솔이 미리 등록해 둔 부하 테스트 대상 목록을 읽는다.
 *
 * <p>대상 선택은 URL 입력칸을 채워주는 편의 기능이므로 파일이 없거나 깨져도 콘솔은 계속 동작한다.
 * 그 경우 목록만 비고 사용자는 URL을 직접 입력한다.
 */
public final class TargetEnvironmentCatalog {

    static final String DEFAULT_FILE_NAME = "environments.properties";

    private TargetEnvironmentCatalog() {
    }

    public static List<TargetEnvironment> load(final Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        final Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            return List.of();
        }

        final String declared = properties.getProperty("targets", "").trim();
        if (declared.isEmpty()) {
            return List.of();
        }

        final List<TargetEnvironment> targets = new ArrayList<>();
        for (String rawKey : declared.split(",")) {
            final String key = rawKey.trim();
            if (key.isEmpty()) {
                continue;
            }
            final String coreBaseUrl = properties.getProperty(key + ".coreBaseUrl", "").trim();
            if (coreBaseUrl.isEmpty()) {
                continue;
            }
            targets.add(new TargetEnvironment(
                    key,
                    properties.getProperty(key + ".label", ""),
                    coreBaseUrl,
                    properties.getProperty(key + ".queueBaseUrl", "")
            ));
        }
        return List.copyOf(targets);
    }
}
