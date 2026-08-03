package com.ticket.loadtest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class RealisticSeatSelection {
    private static final int MAX_DYNAMIC_ATTEMPTS = 3;
    private static final double POPULAR_SEAT_POOL_PERCENT = 10.0;
    private static final double POPULAR_SEAT_SELECTION_PERCENT = 80.0;

    private RealisticSeatSelection() {
    }

    public static List<Long> availableCandidates(final List<Long> available, final Set<Long> attempted) {
        final List<Long> candidates = new ArrayList<>();
        for (Long seatId : available) {
            if (!attempted.contains(seatId)) {
                candidates.add(seatId);
            }
        }
        return List.copyOf(candidates);
    }

    public static long chooseSeat(final List<Long> candidates) {
        final boolean selectPopularSeat =
                ThreadLocalRandom.current().nextDouble(100.0) < POPULAR_SEAT_SELECTION_PERCENT;
        final List<Long> selectionPool = selectionPool(candidates, selectPopularSeat);
        return selectionPool.get(ThreadLocalRandom.current().nextInt(selectionPool.size()));
    }

    static List<Long> selectionPool(final List<Long> candidates, final boolean selectPopularSeat) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        final int popularSeatCount = Math.max(1,
                (int) Math.ceil(candidates.size() * POPULAR_SEAT_POOL_PERCENT / 100.0));
        if (selectPopularSeat || popularSeatCount == candidates.size()) {
            return List.copyOf(candidates.subList(0, popularSeatCount));
        }
        return List.copyOf(candidates.subList(popularSeatCount, candidates.size()));
    }

    public static int maxAttempts(final boolean dynamicSeatSelection) {
        return dynamicSeatSelection ? MAX_DYNAMIC_ATTEMPTS : 2;
    }
}
