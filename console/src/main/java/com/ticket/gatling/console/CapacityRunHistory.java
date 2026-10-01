package com.ticket.gatling.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 결과 폴더에 남은 예매 실행 파일을 모아 수용량 측정 패널에 넘긴다.
 *
 * <p>콘솔을 다시 시작해도 단계별 결과가 남아야 하므로 메모리의 실행 목록이 아니라 폴더를 읽는다.
 * JSON 파서가 없어서 파일 원문을 문자열로 넘기고, 해석과 PASS/FAIL 판정은 화면이 한다.
 */
final class CapacityRunHistory {
    static final String RUN_CONFIG = "booking-run-config.json";
    private static final List<String> FILES = List.of(
            RUN_CONFIG, "booking-evidence.json", "run-metadata.json", LoadTestService.CONSOLE_RUN_FILE);

    private CapacityRunHistory() {
    }

    static String json(final Path reportsRoot) throws IOException {
        if (!Files.isDirectory(reportsRoot)) {
            return "[]";
        }
        try (Stream<Path> directories = Files.list(reportsRoot)) {
            // _latest는 Gatling이 증거 파일을 쓰는 작업 폴더라 실행 결과가 아니다.
            return directories
                    .filter(directory -> !directory.getFileName().toString().startsWith("_"))
                    .filter(directory -> Files.isRegularFile(directory.resolve(RUN_CONFIG)))
                    .map(CapacityRunHistory::runJson)
                    .collect(Collectors.joining(",", "[", "]"));
        }
    }

    /** 한 줄에 회원 ID 하나인 파일의 ID 개수. 파일이 없으면 -1. */
    static long countMemberIds(final Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return -1;
        }
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).count();
        }
    }

    private static String runJson(final Path directory) {
        final StringBuilder json = new StringBuilder("{\"folder\":\"")
                .append(Json.escape(directory.getFileName().toString()))
                .append("\",\"modifiedAt\":")
                .append(Json.nullable(modifiedAt(directory.resolve(RUN_CONFIG))));
        for (String file : FILES) {
            json.append(",\"").append(file).append("\":").append(Json.nullable(read(directory.resolve(file))));
        }
        return json.append('}').toString();
    }

    private static String modifiedAt(final Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant().toString();
        } catch (IOException exception) {
            return null;
        }
    }

    private static String read(final Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException exception) {
            return null;
        }
    }
}
