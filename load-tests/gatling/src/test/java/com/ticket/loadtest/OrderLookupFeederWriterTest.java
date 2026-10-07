package com.ticket.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderLookupFeederWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesAReusableOrderLookupFeederFromSuccessfulOrders() throws Exception {
        Path file = tempDir.resolve("order-lookup.csv");

        OrderLookupFeeder.initialize(file);
        OrderLookupFeeder.append(file, 17L, "ORD-17");

        assertEquals(
                List.of("memberId,orderKey", "17,ORD-17"),
                Files.readAllLines(file)
        );
    }
}
