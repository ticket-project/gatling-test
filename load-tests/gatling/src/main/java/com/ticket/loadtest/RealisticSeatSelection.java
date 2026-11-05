package com.ticket.loadtest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class RealisticSeatSelection {
    private static final int MAX_DYNAMIC_ATTEMPTS = 3;
    private static final double POPULAR_SEAT_POOL_PERCENT = 10.0;
    private static final double POPULAR_SEAT_SELECTION_PERCENT = 80.0;

    private RealisticSeatSelection() {
    }

    /** 배치도의 좌석 목록(seatId 오름차순)에서 점유 좌석을 뺀다. 순서는 배치도 순서를 지켜 인기 좌석 구간이 좌석 전체 응답과 같게 잡힌다. */
    public static List<Long> withoutOccupied(final List<Long> catalog, final Collection<Long> occupied) {
        final Set<Long> occupiedSet = new HashSet<>(occupied);
        final List<Long> available = new ArrayList<>(catalog.size());
        for (Long seatId : catalog) {
            if (!occupiedSet.contains(seatId)) {
                available.add(seatId);
            }
        }
        return available;
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

    public static int maxDynamicAttempts() {
        return MAX_DYNAMIC_ATTEMPTS;
    }

    public static double popularSeatPoolPercent() {
        return POPULAR_SEAT_POOL_PERCENT;
    }

    public static double popularSeatSelectionPercent() {
        return POPULAR_SEAT_SELECTION_PERCENT;
    }
}
