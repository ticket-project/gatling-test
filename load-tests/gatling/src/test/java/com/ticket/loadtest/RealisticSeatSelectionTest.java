package com.ticket.loadtest;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RealisticSeatSelectionTest {

    @Test
    void removesOccupiedSeatsFromTheSeatMapInSeatMapOrder() {
        final List<Long> available = RealisticSeatSelection.withoutOccupied(
                List.of(1L, 2L, 3L, 4L, 5L),
                List.of(4L, 2L, 99L)
        );

        assertEquals(List.of(1L, 3L, 5L), available);
    }

    @Test
    void decodesOccupancyBitsInSeatMapOrderWithEarlierSeatAsHighBit() {
        // 좌석 10개: 1010 0001 / 01(00 0000) -> 1번째·3번째·8번째·10번째 좌석이 선택 가능
        final String base64 = Base64.getEncoder().encodeToString(new byte[]{(byte) 0b1010_0001, (byte) 0b0100_0000});
        final List<Long> catalog = List.of(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);

        assertEquals(List.of(11L, 13L, 18L, 20L),
                RealisticSeatSelection.selectableFromOccupancy(catalog, 10, base64));
    }

    @Test
    void rejectsOccupancyThatDoesNotMatchTheSeatMap() {
        final String base64 = Base64.getEncoder().encodeToString(new byte[]{(byte) 0xFF, (byte) 0xC0});
        final List<Long> catalog = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

        assertThrows(IllegalArgumentException.class,
                () -> RealisticSeatSelection.selectableFromOccupancy(catalog, 11, base64));
        assertThrows(IllegalArgumentException.class,
                () -> RealisticSeatSelection.selectableFromOccupancy(catalog.subList(0, 8), 8, base64));
    }

    @Test
    void excludesPreviouslyAttemptedSeatsWhilePreservingServerOrder() {
        final List<Long> candidates = RealisticSeatSelection.availableCandidates(
                List.of(10L, 20L, 30L, 40L),
                Set.of(20L, 40L)
        );

        assertEquals(List.of(10L, 30L), candidates);
    }

    @Test
    void separatesPopularTenPercentFromTheRemainingSeatPool() {
        final List<Long> candidates = java.util.stream.LongStream.rangeClosed(1, 20)
                .boxed()
                .toList();

        assertEquals(List.of(1L, 2L), RealisticSeatSelection.selectionPool(candidates, true));
        assertEquals(candidates.subList(2, 20), RealisticSeatSelection.selectionPool(candidates, false));
    }

    @Test
    void handlesEmptyAndSingleSeatPools() {
        assertEquals(List.of(), RealisticSeatSelection.selectionPool(List.of(), true));
        assertEquals(List.of(7L), RealisticSeatSelection.selectionPool(List.of(7L), true));
        assertEquals(List.of(7L), RealisticSeatSelection.selectionPool(List.of(7L), false));
    }

    @Test
    void allowsThreeAttemptsOnlyForDynamicSeatSelection() {
        assertEquals(3, RealisticSeatSelection.maxAttempts(true));
        assertEquals(2, RealisticSeatSelection.maxAttempts(false));
    }
}
