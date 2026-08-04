package com.ticket.loadtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class BookingResultRecorder {
    private static final Object LOCK = new Object();
    private static final String HEADER = "scenario,nodeIndex,memberId,seatId,orderKey,httpStatus,result,errorCode,selectAttemptCount,lastStep,"
            + "startedAt,coreAdmittedAt,flowCompletedAt,coreResidenceMillis,timestamp"
            + System.lineSeparator();

    private BookingResultRecorder() {
    }

    public static void append(
            final Path file, final String scenario, final int nodeIndex, final long memberId, final long seatId,
            final String orderKey, final int httpStatus, final String result
    ) {
        append(file, scenario, nodeIndex, memberId, seatId, orderKey, httpStatus, result, "", "", "", "", 0);
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
            final String coreAdmittedAt
    ) {
        append(file, scenario, nodeIndex, memberId, seatId, orderKey, httpStatus, result,
                lastStep, startedAt, coreAdmittedAt, "", 0);
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
                final Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (Files.notExists(file) || Files.size(file) == 0) {
                    Files.writeString(file, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                }
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to append booking result: " + file, exception);
            }
        }
    }

    static void initialize(final Path file) {
        synchronized (LOCK) {
            try {
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
