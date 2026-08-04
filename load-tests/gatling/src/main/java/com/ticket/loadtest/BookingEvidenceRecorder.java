package com.ticket.loadtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class BookingEvidenceRecorder {
    private static final ConcurrentMap<EvidenceKey, EvidenceState> STATES = new ConcurrentHashMap<>();

    private BookingEvidenceRecorder() {
    }

    public static void begin(final Path resultFile, final String scenario, final int nodeIndex) {
        final EvidenceKey key = key(resultFile, scenario, nodeIndex);
        final EvidenceState previous = STATES.putIfAbsent(key, new EvidenceState());
        if (previous != null) {
            throw new IllegalStateException("Booking evidence was already initialized: " + key.resultFile());
        }
        BookingResultRecorder.initialize(key.resultFile());
    }

    public static Instant recordStarted(
            final Path resultFile,
            final String scenario,
            final int nodeIndex,
            final long memberId
    ) {
        final EvidenceState state = state(resultFile, scenario, nodeIndex);
        if (state.startedMembers.putIfAbsent(memberId, Boolean.TRUE) != null) {
            throw new IllegalStateException("Duplicate booking user start: memberId=" + memberId);
        }
        state.startedUsers.incrementAndGet();
        return Instant.now();
    }

    public static Instant recordCoreAdmission(
            final Path resultFile,
            final String scenario,
            final int nodeIndex,
            final long memberId
    ) {
        final Instant admittedAt = Instant.now();
        final EvidenceState state = state(resultFile, scenario, nodeIndex);
        if (state.admittedMembers.putIfAbsent(memberId, admittedAt) != null) {
            throw new IllegalStateException("Duplicate Core admission: memberId=" + memberId);
        }
        state.coreAdmissionsByEpochSecond
                .computeIfAbsent(admittedAt.getEpochSecond(), ignored -> new AtomicLong())
                .incrementAndGet();
        recordActiveDelta(state, admittedAt, 1L);
        return admittedAt;
    }
    public static void recordSeatSelectionConflict(
            final Path resultFile,
            final String scenario,
            final int nodeIndex
    ) {
        state(resultFile, scenario, nodeIndex)
                .seatSelectionConflictAttempts
                .incrementAndGet();
    }


    static long recordTerminal(
            final Path resultFile,
            final String scenario,
            final int nodeIndex,
            final long memberId,
            final String result,
            final Instant completedAt
    ) {
        final EvidenceState state = STATES.get(key(resultFile, scenario, nodeIndex));
        if (state == null) {
            return -1L;
        }
        if (state.terminalMembers.putIfAbsent(memberId, result) != null) {
            throw new IllegalStateException("Duplicate booking terminal result: memberId=" + memberId);
        }
        state.terminalUsers.incrementAndGet();
        if ("QUEUE_TIMEOUT".equals(result)) {
            state.queueTimeouts.incrementAndGet();
        }
        if ("SUCCESS".equals(result)) {
            state.successfulUsers.incrementAndGet();
            state.successfulCompletionsByEpochSecond
                    .computeIfAbsent(completedAt.getEpochSecond(), ignored -> new AtomicLong())
                    .incrementAndGet();
        } else if (result.startsWith("BUSINESS_REJECTED_")) {
            state.businessRejectedUsers.incrementAndGet();
        } else if (result.startsWith("USER_DROPPED_")) {
            state.userDroppedUsers.incrementAndGet();
        } else if (!"QUEUE_TIMEOUT".equals(result)) {
            state.technicalFailureUsers.incrementAndGet();
        }
        final Instant admittedAt = state.admittedMembers.get(memberId);
        if (admittedAt == null) {
            return -1L;
        }
        final long residenceMillis = Math.max(0L, completedAt.toEpochMilli() - admittedAt.toEpochMilli());
        state.coreResidenceMillis.add(residenceMillis);
        recordActiveDelta(state, completedAt, -1L);
        return residenceMillis;
    }

    public static EvidenceSummary verifyAndWrite(
            final Path resultFile,
            final String scenario,
            final int nodeIndex,
            final double queueTimeoutThresholdPercent,
            final int maxCoreAdmissionsPerSecond,
            final double admissionRateTolerancePercent,
            final double technicalFailureThresholdPercent
    ) {
        final EvidenceKey key = key(resultFile, scenario, nodeIndex);
        final EvidenceState state = state(key);
        final long startedUsers = state.startedUsers.get();
        final long terminalUsers = state.terminalUsers.get();
        final long queueTimeouts = state.queueTimeouts.get();
        final long missingTerminalResults = startedUsers - terminalUsers;
        final long successfulUsers = state.successfulUsers.get();
        final long technicalFailureUsers = state.technicalFailureUsers.get();
        final long businessRejectedUsers = state.businessRejectedUsers.get();
        final long userDroppedUsers = state.userDroppedUsers.get();
        final long seatSelectionConflictAttempts = state.seatSelectionConflictAttempts.get();
        final long maxObservedSuccessfulCompletions = state.successfulCompletionsByEpochSecond.values().stream()
                .mapToLong(AtomicLong::get)
                .max()
                .orElse(0L);
        final double technicalFailurePercent = startedUsers == 0
                ? 0.0
                : technicalFailureUsers * 100.0 / startedUsers;
        final long maxObservedCoreAdmissions = state.coreAdmissionsByEpochSecond.values().stream()
                .mapToLong(AtomicLong::get)
                .max()
                .orElse(0L);
        final ActiveSummary activeSummary = activeSummary(state);
        final ResidenceSummary residenceSummary = residenceSummary(state.coreResidenceMillis);
        final double queueTimeoutPercent = startedUsers == 0
                ? 0.0
                : queueTimeouts * 100.0 / startedUsers;
        final long allowedCoreAdmissions = maxCoreAdmissionsPerSecond <= 0
                ? 0L
                : (long) Math.ceil(maxCoreAdmissionsPerSecond * (1.0 + admissionRateTolerancePercent / 100.0));

        final EvidenceSummary summary = new EvidenceSummary(
                scenario,
                nodeIndex,
                startedUsers,
                terminalUsers,
                missingTerminalResults,
                successfulUsers,
                technicalFailureUsers,
                technicalFailurePercent,
                businessRejectedUsers,
                userDroppedUsers,
                seatSelectionConflictAttempts,
                maxObservedSuccessfulCompletions,
                queueTimeouts,
                queueTimeoutPercent,
                maxObservedCoreAdmissions,
                maxCoreAdmissionsPerSecond,
                allowedCoreAdmissions,
                activeSummary.maxActiveUsers(),
                residenceSummary.averageMillis(),
                residenceSummary.p95Millis(),
                residenceSummary.p99Millis()
        );
        writeSummary(
                key.resultFile(),
                summary,
                state.coreAdmissionsByEpochSecond,
                state.successfulCompletionsByEpochSecond,
                activeSummary.samples()
        );

        if (startedUsers <= 0) {
            throw new IllegalStateException("Booking evidence is invalid: no virtual user started");
        }
        if (missingTerminalResults != 0) {
            throw new IllegalStateException("Booking result completeness failed: started="
                    + startedUsers + ", terminal=" + terminalUsers);
        }
        if (queueTimeoutPercent > queueTimeoutThresholdPercent) {
            throw new IllegalStateException("Queue timeout threshold exceeded: actual="
                    + queueTimeoutPercent + "%, threshold=" + queueTimeoutThresholdPercent + "%");
        }
        if (technicalFailurePercent >= technicalFailureThresholdPercent) {
            throw new IllegalStateException("Technical user-flow failure threshold exceeded: actual="
                    + technicalFailurePercent + "%, threshold=" + technicalFailureThresholdPercent + "%");
        }
        if (maxCoreAdmissionsPerSecond > 0 && maxObservedCoreAdmissions > allowedCoreAdmissions) {
            throw new IllegalStateException("Core admission rate exceeded: observed="
                    + maxObservedCoreAdmissions + "/s, allowed=" + allowedCoreAdmissions + "/s");
        }
        return summary;
    }

    private static void writeSummary(
            final Path resultFile,
            final EvidenceSummary summary,
            final Map<Long, AtomicLong> coreAdmissions,
            final Map<Long, AtomicLong> successfulCompletions,
            final List<ActiveSample> activeSamples
    ) {
        final Path parent = Objects.requireNonNullElse(resultFile.toAbsolutePath().getParent(), Path.of("."));
        final Path summaryFile = parent.resolve("booking-evidence.json");
        final Path admissionsFile = parent.resolve("booking-admissions.csv");
        final Path completionsFile = parent.resolve("booking-completions.csv");
        final Path activeUsersFile = parent.resolve("booking-active-users.csv");
        final String json = "{\n"
                + "  \"scenario\":\"" + json(summary.scenario()) + "\",\n"
                + "  \"nodeIndex\":" + summary.nodeIndex() + ",\n"
                + "  \"startedUsers\":" + summary.startedUsers() + ",\n"
                + "  \"terminalUsers\":" + summary.terminalUsers() + ",\n"
                + "  \"missingTerminalResults\":" + summary.missingTerminalResults() + ",\n"
                + "  \"successfulUsers\":" + summary.successfulUsers() + ",\n"
                + "  \"technicalFailureUsers\":" + summary.technicalFailureUsers() + ",\n"
                + "  \"technicalFailurePercent\":" + summary.technicalFailurePercent() + ",\n"
                + "  \"businessRejectedUsers\":" + summary.businessRejectedUsers() + ",\n"
                + "  \"userDroppedUsers\":" + summary.userDroppedUsers() + ",\n"
                + "  \"seatSelectionConflictAttempts\":" + summary.seatSelectionConflictAttempts() + ",\n"
                + "  \"maxObservedSuccessfulCompletionsPerSecond\":"
                + summary.maxObservedSuccessfulCompletionsPerSecond() + ",\n"
                + "  \"queueTimeouts\":" + summary.queueTimeouts() + ",\n"
                + "  \"queueTimeoutPercent\":" + summary.queueTimeoutPercent() + ",\n"
                + "  \"maxObservedCoreAdmissionsPerSecond\":"
                + summary.maxObservedCoreAdmissionsPerSecond() + ",\n"
                + "  \"configuredMaxCoreAdmissionsPerSecond\":"
                + summary.configuredMaxCoreAdmissionsPerSecond() + ",\n"
                + "  \"allowedCoreAdmissionsPerSecond\":" + summary.allowedCoreAdmissionsPerSecond() + ",\n"
                + "  \"maxObservedActiveUsers\":" + summary.maxObservedActiveUsers() + ",\n"
                + "  \"averageCoreResidenceMillis\":" + summary.averageCoreResidenceMillis() + ",\n"
                + "  \"p95CoreResidenceMillis\":" + summary.p95CoreResidenceMillis() + ",\n"
                + "  \"p99CoreResidenceMillis\":" + summary.p99CoreResidenceMillis() + "\n"
                + "}\n";
        final StringBuilder csv = new StringBuilder("epochSecond,count\n");
        coreAdmissions.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getKey))
                .forEach(entry -> csv.append(entry.getKey()).append(',')
                        .append(entry.getValue().get()).append('\n'));
        final StringBuilder activeCsv = new StringBuilder("epochMilli,activeUsers\n");
        final StringBuilder completionCsv = new StringBuilder("epochSecond,count\n");
        successfulCompletions.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getKey))
                .forEach(entry -> completionCsv.append(entry.getKey()).append(',')
                        .append(entry.getValue().get()).append('\n'));
        activeSamples.forEach(sample -> activeCsv.append(sample.epochMilli()).append(',')
                .append(sample.activeUsers()).append('\n'));
        try {
            Files.createDirectories(parent);
            Files.writeString(summaryFile, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(admissionsFile, csv, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(activeUsersFile, activeCsv, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(completionsFile, completionCsv, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write booking evidence beside " + resultFile, exception);
        }
    }

    private static ActiveSummary activeSummary(final EvidenceState state) {
        synchronized (state.activeLock) {
            return new ActiveSummary(state.maxActiveUsers, List.copyOf(state.activeSamples));
        }
    }

    private static void recordActiveDelta(
            final EvidenceState state,
            final Instant occurredAt,
            final long delta
    ) {
        synchronized (state.activeLock) {
            state.currentActiveUsers += delta;
            if (state.currentActiveUsers < 0L) {
                throw new IllegalStateException("Core active users became negative");
            }
            state.maxActiveUsers = Math.max(state.maxActiveUsers, state.currentActiveUsers);
            state.activeSamples.add(new ActiveSample(occurredAt.toEpochMilli(), state.currentActiveUsers));
        }
    }

    private static ResidenceSummary residenceSummary(final ConcurrentLinkedQueue<Long> residenceMillis) {
        if (residenceMillis.isEmpty()) {
            return new ResidenceSummary(0L, 0L, 0L);
        }
        final List<Long> sorted = new ArrayList<>(residenceMillis);
        Collections.sort(sorted);
        final long average = Math.round(sorted.stream().mapToLong(Long::longValue).average().orElse(0.0));
        return new ResidenceSummary(average, percentile(sorted, 0.95), percentile(sorted, 0.99));
    }

    private static long percentile(final List<Long> sorted, final double percentile) {
        final int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(index);
    }

    private static EvidenceState state(
            final Path resultFile,
            final String scenario,
            final int nodeIndex
    ) {
        return state(key(resultFile, scenario, nodeIndex));
    }

    private static EvidenceState state(final EvidenceKey key) {
        final EvidenceState state = STATES.get(key);
        if (state == null) {
            throw new IllegalStateException("Booking evidence was not initialized: " + key.resultFile());
        }
        return state;
    }

    private static EvidenceKey key(final Path resultFile, final String scenario, final int nodeIndex) {
        return new EvidenceKey(resultFile.toAbsolutePath().normalize(), scenario, nodeIndex);
    }

    private static String json(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record EvidenceKey(Path resultFile, String scenario, int nodeIndex) {
    }

    private static final class EvidenceState {
        private final AtomicLong startedUsers = new AtomicLong();
        private final AtomicLong terminalUsers = new AtomicLong();
        private final AtomicLong queueTimeouts = new AtomicLong();
        private final AtomicLong successfulUsers = new AtomicLong();
        private final AtomicLong technicalFailureUsers = new AtomicLong();
        private final AtomicLong businessRejectedUsers = new AtomicLong();
        private final AtomicLong userDroppedUsers = new AtomicLong();
        private final AtomicLong seatSelectionConflictAttempts = new AtomicLong();
        private final ConcurrentMap<Long, Boolean> startedMembers = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, String> terminalMembers = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, AtomicLong> coreAdmissionsByEpochSecond = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, AtomicLong> successfulCompletionsByEpochSecond = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, Instant> admittedMembers = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> coreResidenceMillis = new ConcurrentLinkedQueue<>();
        private final Object activeLock = new Object();
        private final List<ActiveSample> activeSamples = new ArrayList<>();
        private long currentActiveUsers;
        private long maxActiveUsers;
    }

    public record EvidenceSummary(
            String scenario,
            int nodeIndex,
            long startedUsers,
            long terminalUsers,
            long missingTerminalResults,
            long successfulUsers,
            long technicalFailureUsers,
            double technicalFailurePercent,
            long businessRejectedUsers,
            long userDroppedUsers,
            long seatSelectionConflictAttempts,
            long maxObservedSuccessfulCompletionsPerSecond,
            long queueTimeouts,
            double queueTimeoutPercent,
            long maxObservedCoreAdmissionsPerSecond,
            int configuredMaxCoreAdmissionsPerSecond,
            long allowedCoreAdmissionsPerSecond,
            long maxObservedActiveUsers,
            long averageCoreResidenceMillis,
            long p95CoreResidenceMillis,
            long p99CoreResidenceMillis
    ) {
    }

    private record ActiveSummary(long maxActiveUsers, List<ActiveSample> samples) {
    }

    private record ActiveSample(long epochMilli, long activeUsers) {
    }

    private record ResidenceSummary(long averageMillis, long p95Millis, long p99Millis) {
    }
}
