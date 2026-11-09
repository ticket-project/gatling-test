package com.ticket.loadtest;

import java.util.ArrayList;
import java.util.Base64;
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

    /**
     * occupancy 응답(좌석당 1비트, base64)을 배치도 좌석(seatId 오름차순)에 맞춰 선택 가능 좌석으로 푼다.
     * 비트 1이 선택 가능이고 한 바이트 안에서는 앞 좌석이 최상위 비트다. 결과 순서는 withoutOccupied와 같다.
     *
     * @throws IllegalArgumentException 좌석 수나 비트 길이가 배치도와 맞지 않거나 base64가 깨졌을 때
     */
    public static List<Long> selectableFromOccupancy(final List<Long> catalog, final int seatCount, final String base64) {
        final byte[] bits = Base64.getDecoder().decode(base64);
        if (seatCount != catalog.size() || bits.length != (catalog.size() + 7) / 8) {
            throw new IllegalArgumentException("occupancy seatCount=" + seatCount + " bytes=" + bits.length
                    + " does not match seat map size " + catalog.size());
        }
        final List<Long> available = new ArrayList<>(catalog.size());
        for (int i = 0; i < catalog.size(); i++) {
            if ((bits[i >>> 3] & (0x80 >>> (i & 7))) != 0) {
                available.add(catalog.get(i));
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
