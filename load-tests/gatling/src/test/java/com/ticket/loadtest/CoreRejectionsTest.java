package com.ticket.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ticket.loadtest.CoreRejections.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreRejectionsTest {

    @Test
    void selectTreatsSeatContentionAsBusinessAndLockTimeoutAsOverload() {
        assertEquals(Kind.BUSINESS_REJECTED, CoreRejections.ofSelect(409, "E4001"));
        assertEquals(Kind.BUSINESS_REJECTED, CoreRejections.ofSelect(409, "E6000"));
        assertEquals(Kind.BUSINESS_REJECTED, CoreRejections.ofSelect(409, "E6001"));
        assertEquals(Kind.OVERLOADED, CoreRejections.ofSelect(409, "E6003"));
        assertEquals(Kind.TECHNICAL, CoreRejections.ofSelect(409, "E5004"));
    }

    @Test
    void orderTreatsPendingOrderAndHoldLimitAsBusinessAndLockTimeoutAsOverload() {
        assertEquals(Kind.BUSINESS_REJECTED, CoreRejections.ofOrder(409, "E4006"));
        assertEquals(Kind.BUSINESS_REJECTED, CoreRejections.ofOrder(409, "E4007"));
        assertEquals(Kind.BUSINESS_REJECTED, CoreRejections.ofOrder(409, "E5004"));
        assertEquals(Kind.BUSINESS_REJECTED, CoreRejections.ofOrder(409, "E6000"));
        assertEquals(Kind.BUSINESS_REJECTED, CoreRejections.ofOrder(409, "E6001"));
        assertEquals(Kind.OVERLOADED, CoreRejections.ofOrder(409, "E6003"));
    }

    @Test
    void codesCoreDoesNotHaveAndNon409StatusesAreTechnical() {
        assertEquals(Kind.TECHNICAL, CoreRejections.ofOrder(409, "E5000"));
        assertEquals(Kind.TECHNICAL, CoreRejections.ofOrder(409, "E5001"));
        assertEquals(Kind.TECHNICAL, CoreRejections.ofOrder(409, null));
        assertEquals(Kind.TECHNICAL, CoreRejections.ofSelect(500, "E4001"));
    }

    @Test
    void gatlingAcceptsEveryClassifiedCodeSoOverloadIsNotCountedAsKo() {
        assertEquals(List.of("E4001", "E6000", "E6001", "E6003"), CoreRejections.classifiedSelectCodes());
        assertEquals(List.of("E4006", "E4007", "E5004", "E6000", "E6001", "E6003"), CoreRejections.classifiedOrderCodes());
    }

    @Test
    void resultNamesKeepBusinessAndOverloadPrefixesApart() {
        assertEquals("BUSINESS_REJECTED_E4001", CoreRejections.resultName(Kind.BUSINESS_REJECTED, "E4001"));
        assertEquals("OVERLOADED_E6003", CoreRejections.resultName(Kind.OVERLOADED, "E6003"));
    }
}
