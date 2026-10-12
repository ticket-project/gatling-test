package com.ticket.loadtest;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class BookingResultRecorder {
    private static final Object LOCK = new Object();
    // 결과 파일을 사용자마다 열고 닫으면 그 비용(백신 검사 포함)이 Gatling 스레드와 이 잠금을 막아 사용자 투입이 흔들렸다
    // (capacity-log P-002). 파일당 한 번 열어 두고 한 줄씩 쓴 뒤 flush한다. 닫기는 JVM 종료에 맡긴다.
    private static final Map<Path, Writer> WRITERS = new HashMap<>();
    private static final String HEADER = "scenario,nodeIndex,memberId,seatId,orderKey,httpStatus,result,errorCode,selectAttemptCount,lastStep,"
            + "startedAt,coreAdmittedAt,flowCompletedAt,coreResidenceMillis,timestamp"
            + System.lineSeparator();

    private BookingResultRecorder() {
    }

    public static void append(
            final Path file,
            final String scenario,
            final int nodeIndex,
            final long memberId,
            final long seatId,
            final String orderKey,
            final int httpStatus,
            final String result,
            final String lastStep,
            final String startedAt,
            final String coreAdmittedAt,
            final String errorCode,
            final int selectAttemptCount
    ) {
        final Instant completedAt = Instant.now();
        final long coreResidenceMillis = BookingEvidenceRecorder.recordTerminal(
                file,
                scenario,
                nodeIndex,
                memberId,
                result,
                completedAt
        );
        final String line = String.join(",", csv(scenario), Integer.toString(nodeIndex), Long.toString(memberId),
                Long.toString(seatId), csv(orderKey), Integer.toString(httpStatus), csv(result), csv(errorCode),
                Integer.toString(selectAttemptCount), csv(lastStep),
                csv(startedAt), csv(coreAdmittedAt), csv(completedAt.toString()),
                coreResidenceMillis < 0 ? "" : Long.toString(coreResidenceMillis), csv(completedAt.toString()))
                + System.lineSeparator();
        synchronized (LOCK) {
            try {
                final Writer writer = writer(file);
                writer.write(line);
                writer.flush();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to append booking result: " + file, exception);
            }
        }
    }

    private static Writer writer(final Path file) throws IOException {
        final Path key = file.toAbsolutePath().normalize();
        Writer writer = WRITERS.get(key);
        if (writer == null) {
            final Path parent = key.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            final boolean empty = Files.notExists(key) || Files.size(key) == 0;
            writer = Files.newBufferedWriter(key, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (empty) {
                writer.write(HEADER);
            }
            WRITERS.put(key, writer);
        }
        return writer;
    }

    static void initialize(final Path file) {
        synchronized (LOCK) {
            try {
                final Writer open = WRITERS.remove(file.toAbsolutePath().normalize());
                if (open != null) {
                    open.close();
                }
                final Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to initialize booking result: " + file, exception);
            }
        }
    }

    private static String csv(final String value) {
        final String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
