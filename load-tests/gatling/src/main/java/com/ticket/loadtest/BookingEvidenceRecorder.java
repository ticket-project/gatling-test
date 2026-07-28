package com.ticket.loadtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
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
            final int nodeIndex
    ) {
        final Instant admittedAt = Instant.now();
        state(resultFile, scenario, nodeIndex)
                .coreAdmissionsByEpochSecond
                .computeIfAbsent(admittedAt.getEpochSecond(), ignored -> new AtomicLong())
                .incrementAndGet();
        return admittedAt;
    }

    static void recordTerminal(
            final Path resultFile,
            final String scenario,
            final int nodeIndex,
            final long memberId,
            final String result
    ) {
        final EvidenceState state = STATES.get(key(resultFile, scenario, nodeIndex));
        if (state == null) {
            return;
        }
        if (state.terminalMembers.putIfAbsent(memberId, result) != null) {
            throw new IllegalStateException("Duplicate booking terminal result: memberId=" + memberId);
        }
        state.terminalUsers.incrementAndGet();
        if ("QUEUE_TIMEOUT".equals(result)) {
            state.queueTimeouts.incrementAndGet();
        }
    }

    public static EvidenceSummary verifyAndWrite(
            final Path resultFile,
            final String scenario,
            final int nodeIndex,
            final double queueTimeoutThresholdPercent,
            final int maxCoreAdmissionsPerSecond,
            final double admissionRateTolerancePercent
    ) {
        final EvidenceKey key = key(resultFile, scenario, nodeIndex);
        final EvidenceState state = state(key);
        final long startedUsers = state.startedUsers.get();
        final long terminalUsers = state.terminalUsers.get();
        final long queueTimeouts = state.queueTimeouts.get();
        final long missingTerminalResults = startedUsers - terminalUsers;
        final long maxObservedCoreAdmissions = state.coreAdmissionsByEpochSecond.values().stream()
                .mapToLong(AtomicLong::get)
                .max()
                .orElse(0L);
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
                queueTimeouts,
                queueTimeoutPercent,
                maxObservedCoreAdmissions,
                maxCoreAdmissionsPerSecond,
                allowedCoreAdmissions
        );
        writeSummary(key.resultFile(), summary, state.coreAdmissionsByEpochSecond);

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
        if (maxCoreAdmissionsPerSecond > 0 && maxObservedCoreAdmissions > allowedCoreAdmissions) {
            throw new IllegalStateException("Core admission rate exceeded: observed="
                    + maxObservedCoreAdmissions + "/s, allowed=" + allowedCoreAdmissions + "/s");
        }
        return summary;
    }

    private static void writeSummary(
            final Path resultFile,
            final EvidenceSummary summary,
            final Map<Long, AtomicLong> coreAdmissions
    ) {
        final Path parent = Objects.requireNonNullElse(resultFile.toAbsolutePath().getParent(), Path.of("."));
        final Path summaryFile = parent.resolve("booking-evidence.json");
        final Path admissionsFile = parent.resolve("booking-admissions.csv");
        final String json = "{\n"
                + "  \"scenario\":\"" + json(summary.scenario()) + "\",\n"
                + "  \"nodeIndex\":" + summary.nodeIndex() + ",\n"
                + "  \"startedUsers\":" + summary.startedUsers() + ",\n"
                + "  \"terminalUsers\":" + summary.terminalUsers() + ",\n"
                + "  \"missingTerminalResults\":" + summary.missingTerminalResults() + ",\n"
                + "  \"queueTimeouts\":" + summary.queueTimeouts() + ",\n"
                + "  \"queueTimeoutPercent\":" + summary.queueTimeoutPercent() + ",\n"
                + "  \"maxObservedCoreAdmissionsPerSecond\":"
                + summary.maxObservedCoreAdmissionsPerSecond() + ",\n"
                + "  \"configuredMaxCoreAdmissionsPerSecond\":"
                + summary.configuredMaxCoreAdmissionsPerSecond() + ",\n"
                + "  \"allowedCoreAdmissionsPerSecond\":" + summary.allowedCoreAdmissionsPerSecond() + "\n"
                + "}\n";
        final StringBuilder csv = new StringBuilder("epochSecond,count\n");
        coreAdmissions.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getKey))
                .forEach(entry -> csv.append(entry.getKey()).append(',')
                        .append(entry.getValue().get()).append('\n'));
        try {
            Files.createDirectories(parent);
            Files.writeString(summaryFile, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(admissionsFile, csv, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write booking evidence beside " + resultFile, exception);
        }
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
        private final ConcurrentMap<Long, Boolean> startedMembers = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, String> terminalMembers = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, AtomicLong> coreAdmissionsByEpochSecond = new ConcurrentHashMap<>();
    }

    public record EvidenceSummary(
            String scenario,
            int nodeIndex,
            long startedUsers,
            long terminalUsers,
            long missingTerminalResults,
            long queueTimeouts,
            double queueTimeoutPercent,
            long maxObservedCoreAdmissionsPerSecond,
            int configuredMaxCoreAdmissionsPerSecond,
            long allowedCoreAdmissionsPerSecond
    ) {
    }
}