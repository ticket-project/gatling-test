package com.ticket.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestLatencyRecorderTest {

    @Test
    void percentileIsTheFirstMillisecondWhoseCumulativeCountReachesTheShare() {
        final AtomicIntegerArray histogram = new AtomicIntegerArray(RequestLatencyRecorder.MAX_MILLIS + 1);
        for (int millis = 1; millis <= 100; millis++) {
            histogram.incrementAndGet(millis);
        }

        assertEquals(95, RequestLatencyRecorder.percentile(histogram, 100, 0.95));
        assertEquals(99, RequestLatencyRecorder.percentile(histogram, 100, 0.99));
    }

    @Test
    void writesOneRowPerMinuteAndRequestWithSlowResponsesCappedAtTenSeconds(@TempDir final Path dir) throws Exception {
        final long minute = 29_000_000L;
        RequestLatencyRecorder.record("select seat", minute * 60_000, 10);
        RequestLatencyRecorder.record("select seat", minute * 60_000 + 59_999, 60_000);
        RequestLatencyRecorder.record("select seat", (minute + 1) * 60_000, 20);

        final Path file = dir.resolve("request-minutes.csv");
        RequestLatencyRecorder.write(file);

        final List<String> lines = Files.readAllLines(file);
        assertEquals("epochMinute,request,count,p95Ms,p99Ms", lines.getFirst());
        assertEquals(minute + ",select seat,2,10000,10000", lines.get(1));
        assertEquals((minute + 1) + ",select seat,1,20,20", lines.get(2));
    }
}
