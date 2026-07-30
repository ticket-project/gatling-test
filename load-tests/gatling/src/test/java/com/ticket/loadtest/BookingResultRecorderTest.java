package com.ticket.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingResultRecorderTest {
    @TempDir
    Path tempDir;

    @Test
    void writesHeaderOnceAndAppendsEscapedRowsWithoutTokens() throws IOException {
        final Path file = tempDir.resolve("nested/results.csv");

        BookingResultRecorder.append(file, "BOOKING_CAPACITY", 1, 10, 20, "order,\"one\"", 200, "SUCCESS");
        BookingResultRecorder.append(file, "BOOKING_CAPACITY", 1, 11, 21, null, 409, "SOLD_OUT");

        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertEquals("scenario,nodeIndex,memberId,seatId,orderKey,httpStatus,result,lastStep,startedAt,"
                + "coreAdmittedAt,flowCompletedAt,coreResidenceMillis,timestamp", lines.getFirst());
        assertTrue(lines.get(1).startsWith("\"BOOKING_CAPACITY\",1,10,20,\"order,\"\"one\"\"\",200,\"SUCCESS\","));
        assertTrue(lines.get(2).startsWith("\"BOOKING_CAPACITY\",1,11,21,\"\",409,\"SOLD_OUT\","));
        final String content = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(content.contains("accessToken"));
        assertFalse(content.contains("admissionToken"));
    }
}
