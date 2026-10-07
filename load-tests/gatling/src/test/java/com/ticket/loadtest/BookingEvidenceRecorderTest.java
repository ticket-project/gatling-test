package com.ticket.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingEvidenceRecorderTest {
    @TempDir
    Path tempDir;

    @Test
    void verifiesEveryStartedUserHasExactlyOneTerminalResult() {
        final Path resultFile = tempDir.resolve("complete/results.csv");
        BookingEvidenceRecorder.begin(resultFile, "COMPLETE", 0);
        BookingEvidenceRecorder.recordStarted(resultFile, "COMPLETE", 0, 1L);
        BookingEvidenceRecorder.recordStarted(resultFile, "COMPLETE", 0, 2L);
        BookingEvidenceRecorder.recordCoreAdmission(resultFile, "COMPLETE", 0, 1L);
        BookingEvidenceRecorder.recordCoreAdmission(resultFile, "COMPLETE", 0, 2L);
        BookingResultRecorder.append(resultFile, "COMPLETE", 0, 1L, 11L, "order-1", 201, "SUCCESS", "", "", "", "", 0);
        BookingResultRecorder.append(resultFile, "COMPLETE", 0, 2L, 12L, "order-2", 201, "SUCCESS", "", "", "", "", 0);

        final BookingEvidenceRecorder.EvidenceSummary summary = BookingEvidenceRecorder.verifyAndWrite(
                resultFile, "COMPLETE", 0, 0.0, 0, 0.0, 1.0
        );

        assertEquals(2L, summary.startedUsers());
        assertEquals(2L, summary.terminalUsers());
        assertEquals(0L, summary.missingTerminalResults());
        assertEquals(2L, summary.maxObservedActiveUsers());
        assertTrue(Files.isRegularFile(resultFile.resolveSibling("booking-evidence.json")));
        assertTrue(Files.isRegularFile(resultFile.resolveSibling("booking-admissions.csv")));
        assertTrue(Files.isRegularFile(resultFile.resolveSibling("booking-active-users.csv")));
        assertTrue(Files.isRegularFile(resultFile.resolveSibling("booking-completions.csv")));
    }

    @Test
    void countsOverloadApartFromBusinessRejectionsAndTechnicalFailures() throws Exception {
        final Path resultFile = tempDir.resolve("overload/results.csv");
        BookingEvidenceRecorder.begin(resultFile, "OVERLOAD", 0);
        for (long memberId = 1L; memberId <= 3L; memberId++) {
            BookingEvidenceRecorder.recordStarted(resultFile, "OVERLOAD", 0, memberId);
        }
        BookingResultRecorder.append(resultFile, "OVERLOAD", 0, 1L, 11L, "order-1", 201, "SUCCESS", "", "", "", "", 0);
        BookingResultRecorder.append(resultFile, "OVERLOAD", 0, 2L, 12L, null, 409, "BUSINESS_REJECTED_E6000", "", "", "", "", 0);
        BookingResultRecorder.append(resultFile, "OVERLOAD", 0, 3L, 13L, null, 409, "OVERLOADED_E6003", "", "", "", "", 0);

        final BookingEvidenceRecorder.EvidenceSummary summary = BookingEvidenceRecorder.verifyAndWrite(
                resultFile, "OVERLOAD", 0, 0.0, 0, 0.0, 1.0
        );

        assertEquals(1L, summary.businessRejectedUsers());
        assertEquals(1L, summary.overloadedUsers());
        assertEquals(0L, summary.technicalFailureUsers());
        assertTrue(Files.readString(resultFile.resolveSibling("booking-evidence.json")).contains("\"overloadedUsers\":1"));
    }

    @Test
    void failsWhenATerminalResultIsMissing() {
        final Path resultFile = tempDir.resolve("missing/results.csv");
        BookingEvidenceRecorder.begin(resultFile, "MISSING", 0);
        BookingEvidenceRecorder.recordStarted(resultFile, "MISSING", 0, 1L);

        assertThrows(IllegalStateException.class, () -> BookingEvidenceRecorder.verifyAndWrite(
                resultFile, "MISSING", 0, 0.0, 0, 0.0, 1.0
        ));
    }

    @Test
    void failsWhenQueueTimeoutThresholdIsExceeded() {
        final Path resultFile = tempDir.resolve("timeout/results.csv");
        BookingEvidenceRecorder.begin(resultFile, "TIMEOUT", 0);
        BookingEvidenceRecorder.recordStarted(resultFile, "TIMEOUT", 0, 1L);
        BookingResultRecorder.append(resultFile, "TIMEOUT", 0, 1L, 11L, null, 0, "QUEUE_TIMEOUT", "", "", "", "", 0);

        assertThrows(IllegalStateException.class, () -> BookingEvidenceRecorder.verifyAndWrite(
                resultFile, "TIMEOUT", 0, 0.0, 0, 0.0, 1.0
        ));
    }
}
