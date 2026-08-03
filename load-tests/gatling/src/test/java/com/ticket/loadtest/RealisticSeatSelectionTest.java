package com.ticket.loadtest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealisticSeatSelectionTest {

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
