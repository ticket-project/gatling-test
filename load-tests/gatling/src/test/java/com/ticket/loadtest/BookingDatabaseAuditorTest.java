package com.ticket.loadtest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingDatabaseAuditorTest {
    @Test
    void parsesEscapedCsvValues() {
        assertEquals(
                List.of("SUCCESS", "order,\"one\"", "201"),
                BookingDatabaseAuditor.parseCsvLine("\"SUCCESS\",\"order,\"\"one\"\"\",201")
        );
    }

    @Test
    void passesOnlyWhenClientAndDatabaseOrdersMatchWithoutDuplicates() {
        final BookingDatabaseAuditor.AuditResult passed = new BookingDatabaseAuditor.AuditResult(
                1L, 10L, 10L, 0L, 0L, 0L, 0L, 0L
        );
        final BookingDatabaseAuditor.AuditResult hiddenOrder = new BookingDatabaseAuditor.AuditResult(
                1L, 10L, 11L, 0L, 0L, 0L, 0L, 0L
        );

        assertTrue(passed.passed());
        assertFalse(hiddenOrder.passed());
    }
}