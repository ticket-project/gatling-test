package com.ticket.loadtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * 요청 이름과 1분 구간(epoch minute)별 응답 시간 분포를 모은다. 긴 실행에서 끝 몇 분만 무너진 것을 실행 전체 백분위가
 * 가리지 않도록 scripts/capacity/run-step.ps1이 1분마다 p95·p99를 판정한다(core-capacity.md 판정 기준).
 *
 * <p>요청마다 파일에 쓰지 않고 메모리 히스토그램에 더한 뒤 실행이 끝날 때 한 번 쓴다. 요청마다 파일을 쓰면 Gatling 스레드가
 * 막혀 입장이 흔들린다(capacity-log P-002).
 */
public final class RequestLatencyRecorder {
    // ponytail: 1ms 칸 10초까지, 넘으면 마지막 칸. 합격선이 2초 이하라 그 위는 "10초 이상"으로만 알면 된다.
    static final int MAX_MILLIS = 10_000;
    private static final ConcurrentMap<String, AtomicIntegerArray> HISTOGRAMS = new ConcurrentHashMap<>();

    private RequestLatencyRecorder() {
    }

    public static void record(final String request, final long completedEpochMillis, final long responseMillis) {
        final String key = (completedEpochMillis / 60_000) + "," + request;
        HISTOGRAMS.computeIfAbsent(key, ignored -> new AtomicIntegerArray(MAX_MILLIS + 1))
                .incrementAndGet((int) Math.min(Math.max(responseMillis, 0), MAX_MILLIS));
    }

    /** epochMinute,request,count,p95Ms,p99Ms. 기록이 없으면 쓰지 않는다. */
    public static void write(final Path file) {
        if (HISTOGRAMS.isEmpty()) {
            return;
        }
        final List<String> lines = new ArrayList<>();
        lines.add("epochMinute,request,count,p95Ms,p99Ms");
        for (Map.Entry<String, AtomicIntegerArray> entry : new TreeMap<>(HISTOGRAMS).entrySet()) {
            final AtomicIntegerArray histogram = entry.getValue();
            long count = 0;
            for (int millis = 0; millis < histogram.length(); millis++) {
                count += histogram.get(millis);
            }
            lines.add(entry.getKey() + "," + count + "," + percentile(histogram, count, 0.95) + ","
                    + percentile(histogram, count, 0.99));
        }
        try {
            final Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Request latency minutes could not be written: " + file, exception);
        }
    }

    /** 누적 개수가 count × p 이상이 되는 첫 칸(ms). Gatling 리포트와 같은 "이하" 기준이다. */
    static int percentile(final AtomicIntegerArray histogram, final long count, final double p) {
        final double target = Math.ceil(count * p);
        long seen = 0;
        for (int millis = 0; millis < histogram.length(); millis++) {
            seen += histogram.get(millis);
            if (seen >= target) {
                return millis;
            }
        }
        return MAX_MILLIS;
    }
}
