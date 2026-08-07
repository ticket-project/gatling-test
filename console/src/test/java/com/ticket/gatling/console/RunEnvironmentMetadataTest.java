package com.ticket.gatling.console;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEnvironmentMetadataTest {

    @Test
    void parsesDatadogCredentialsOnlyFromCodexMcpSection() {
        final Map<String, String> environment = DatadogEnvironmentClient.parseMcpEnvironment(List.of(
                "[mcp_servers.other.env]",
                "DATADOG_API_KEY = 'ignored-api-key'",
                "[mcp_servers.datadog.env]",
                "DATADOG_API_KEY = 'api-test'",
                "DATADOG_APP_KEY = \"app-test\"",
                "DATADOG_SITE = 'us5.datadoghq.com'",
                "[windows]",
                "DATADOG_API_KEY = 'also-ignored'"
        ));

        assertEquals("api-test", environment.get("DATADOG_API_KEY"));
        assertEquals("app-test", environment.get("DATADOG_APP_KEY"));
        assertEquals("us5.datadoghq.com", environment.get("DATADOG_SITE"));
    }

    @Test
    void parsesDecimalDatadogTimestampsAndKeepsLatestPoint() throws IOException {
        final List<MetricSeries> series = DatadogEnvironmentClient.parseSeries("""
                {
                  "status": "ok",
                  "series": [
                    {
                      "scope": "env:prod,host:i-queue,service:ticket-queue,version:25.0.3_9-lts,version:latest",
                      "pointlist": [[1785195000000.0, 9.0], [1.785195015E12, 10.0]]
                    }
                  ]
                }
                """);

        assertEquals(1, series.size());
        assertEquals("i-queue", series.getFirst().tags().get("host"));
        assertEquals(1785195015000L, series.getFirst().timestamp());
        assertEquals(10.0, series.getFirst().value());
    }

    @Test
    void keepsAllFreshScaledOutHostsAndDropsDeploymentHistory() throws IOException {
        final MetricQueryResult identity = new MetricQueryResult("identity", List.of(
                new MetricSeries("host:i-queue-a", Map.of("host", "i-queue-a"), 1_000_000L, 1.0),
                new MetricSeries("host:i-queue-b", Map.of("host", "i-queue-b"), 950_000L, 1.0),
                new MetricSeries("host:i-stale", Map.of("host", "i-stale"), 800_000L, 1.0)
        ));

        assertEquals(
                List.of("i-queue-a", "i-queue-b"),
                DatadogEnvironmentClient.resolveActiveHosts(identity, 1_010_000L)
        );
    }

    @Test
    void rejectsIdentitySeriesWhenEveryHostIsStale() {
        final MetricQueryResult identity = new MetricQueryResult("identity", List.of(
                new MetricSeries("host:i-old", Map.of("host", "i-old"), 1_000_000L, 1.0)
        ));

        final IOException error = assertThrows(
                IOException.class,
                () -> DatadogEnvironmentClient.resolveActiveHosts(identity, 2_000_000L)
        );

        assertTrue(error.getMessage().contains("recently active host"));
    }

    @Test
    void retriesTemporaryHttpFailureAndEmptyIdentityResponseBeforeCapturingHost() throws Exception {
        final AtomicInteger identityRequests = new AtomicInteger();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", exchange -> {
            final boolean identityQuery = exchange.getRequestURI().getRawQuery().contains("ticket_queue.jvm_info");
            final int identityAttempt = identityQuery ? identityRequests.incrementAndGet() : 0;
            final int statusCode = identityQuery && identityAttempt == 1 ? 500 : 200;
            final String response;
            if (!identityQuery || identityAttempt < 3) {
                response = "{\"status\":\"ok\",\"series\":[]}";
            } else {
                response = """
                        {"status":"ok","series":[{
                          "scope":"env:prod,service:ticket-queue,host:i-queue,version:25.0.3_9-lts",
                          "pointlist":[[%d.0,1.0]]
                        }]}
                        """.formatted(Instant.now().toEpochMilli());
            }
            final byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            final DatadogEnvironmentClient client = new DatadogEnvironmentClient(
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "api-test",
                    "app-test"
            );

            final List<DatadogRuntimeSnapshot> snapshots = client.capture(
                    DatadogTargetInput.queue("https://queue.oneticket.site")
            );

            assertEquals(3, identityRequests.get());
            assertEquals(1, snapshots.size());
            assertEquals("i-queue", snapshots.getFirst().host());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void selectsQueueTargetForQueueOnlySimulation() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("queue-join-only"),
                "baseUrl", List.of("https://queue.oneticket.site")
        ));

        final DatadogTargetInput target = request.environment().targets().getFirst();

        assertEquals(1, request.environment().targets().size());
        assertEquals("queue", target.role());
        assertEquals("ticket-queue", target.datadogService());
        assertEquals("ticket_queue", target.datadogMetricPrefix());
        assertEquals("ticket-queue", target.datadogContainerName());
    }

    @Test
    void selectsCoreTargetForCoreOnlySimulation() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("booking-capacity"),
                "coreBaseUrl", List.of("https://oneticket.site")
        ));

        final DatadogTargetInput target = request.environment().targets().getFirst();

        assertEquals(1, request.environment().targets().size());
        assertEquals("core", target.role());
        assertEquals("ticket-be", target.datadogService());
        assertEquals("ticket", target.datadogMetricPrefix());
        assertEquals("ticket-be", target.datadogContainerName());
    }

    @Test
    void selectsQueueAndCoreTargetsForEndToEndSimulation() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("ticket-open-end-to-end"),
                "coreBaseUrl", List.of("https://oneticket.site"),
                "queueBaseUrl", List.of("https://queue.oneticket.site")
        ));

        assertEquals(List.of("queue", "core"), request.environment().targets().stream()
                .map(DatadogTargetInput::role)
                .toList());
    }

    @Test
    void mapsEverySimulationToItsAutomaticDatadogTargetRoles() {
        for (SimulationType simulationType : SimulationType.values()) {
            final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                    "simulation", List.of(simulationType.key())
            ));
            final List<String> expectedRoles = switch (simulationType) {
                case QUEUE_JOIN_ONLY, QUEUE_ENTER, LEGACY_QUEUE_STATUS, CDN_PUBLIC_STATE -> List.of("queue");
                case BOOKING_CAPACITY, SEAT_CONTENTION,
                        CORE_PERFORMANCE_SUMMARY_API, CORE_SEAT_STATUS_API, CORE_SEAT_SELECT_API,
                        CORE_ORDER_CREATE_API, CORE_ORDER_GET_API, SMOKE, HOT_SEAT_CONCURRENCY,
                        CORE_ADMISSION_CAPACITY, CORE_REALISTIC_CONTENTION, CORE_ACTIVE_USERS_CLOSED, CORE_SPIKE -> List.of("core");
                case TICKET_OPEN_END_TO_END, QUEUE_PROTECTS_CORE -> List.of("queue", "core");
            };

            assertEquals(
                    expectedRoles,
                    request.environment().targets().stream().map(DatadogTargetInput::role).toList(),
                    simulationType.key()
            );
        }
    }

    @Test
    void serializesAutomaticallySelectedQueueMetadata() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("queue-join-only"),
                "baseUrl", List.of("https://queue.oneticket.site")
        ));
        final Instant observedAt = Instant.parse("2026-07-28T05:00:00Z");
        final RunEnvironmentClient client = ignored -> List.of(queueSnapshot(
                "i-queue",
                observedAt,
                4,
                8_589_934_592L,
                "a91b32f"
        ));

        final RunEnvironmentMetadata metadata = RunEnvironmentMetadata.capture(request, client);
        final RuntimeTargetGroupMetadata target = metadata.targets().getFirst();
        final RuntimeInstanceMetadata instance = target.instances().getFirst();
        final UUID runId = UUID.fromString("f8290000-0000-0000-0000-000000000001");
        final String json = metadata.toJson(runId);
        final String description = metadata.runDescription(runId);

        assertEquals("captured", metadata.captureStatus());
        assertEquals("queue", target.role());
        assertEquals("captured", target.captureStatus());
        assertEquals("ticket-queue", target.datadogService());
        assertEquals(observedAt, instance.observedAt());
        assertEquals(4, instance.vcpu());
        assertNull(instance.hikariMaximumPoolSize());
        assertTrue(json.contains("\"schemaVersion\": 4"));
        assertTrue(json.contains("\"capturePhase\": \"preRun\""));
        assertTrue(json.contains("\"replicaCountObserved\": 1"));
        assertTrue(json.contains("\"id\": \"container-i-queue\""));
        assertTrue(json.contains("\"granularity\": \"host\""));
        assertTrue(json.contains("\"service\": \"ticket-queue\""));
        assertTrue(json.contains("\"jvm.xmsBytes\": \"not_explicit\""));
        assertTrue(description.contains("runId=f8290000"));
        assertTrue(description.contains("queue=4vCPU/8GiB"));
        assertTrue(description.contains("queueDocker=2CPU/4GiB"));
    }

    @Test
    void serializesEveryFreshScaledOutQueueHost() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("queue-join-only")
        ));
        final RunEnvironmentClient client = ignored -> List.of(
                queueSnapshot("i-queue-a"),
                queueSnapshot("i-queue-b")
        );

        final RunEnvironmentMetadata metadata = RunEnvironmentMetadata.capture(request, client);
        final String json = metadata.toJson(UUID.randomUUID());
        final RuntimeTargetGroupMetadata queue = metadata.targets().getFirst();

        assertEquals(List.of("i-queue-a", "i-queue-b"), queue.instances().stream()
                .map(RuntimeInstanceMetadata::host)
                .toList());
        assertTrue(json.contains("\"replicaCountObserved\": 2"));
        assertTrue(json.contains("\"host\": \"i-queue-a\""));
        assertTrue(json.contains("\"host\": \"i-queue-b\""));
        assertTrue(metadata.runDescription(UUID.randomUUID()).contains("queueInstances=2"));
        assertTrue(metadata.runDescription(UUID.randomUUID()).contains("queue=2x4vCPU/8GiB"));
    }

    @Test
    void warnsAndMarksRunDescriptionWhenScaledOutHostsAreHeterogeneous() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("queue-join-only")
        ));
        final RunEnvironmentClient client = ignored -> List.of(
                queueSnapshot("i-queue-a", Instant.parse("2026-07-28T05:00:00Z"), 4, 8_589_934_592L, "a91b32f"),
                queueSnapshot("i-queue-b", Instant.parse("2026-07-28T05:00:01Z"), 8, 8_589_934_592L, "b82c43e")
        );

        final RunEnvironmentMetadata metadata = RunEnvironmentMetadata.capture(request, client);
        final String description = metadata.runDescription(UUID.randomUUID());

        assertEquals("captured", metadata.captureStatus());
        assertTrue(metadata.captureWarnings().stream()
                .anyMatch(value -> value.contains("heterogeneous across active hosts: machine.vcpu")));
        assertTrue(metadata.captureWarnings().stream()
                .anyMatch(value -> value.contains("heterogeneous across active hosts: application.commit")));
        assertTrue(description.contains("queueCommit=mixed"));
        assertTrue(description.contains("queue=mixed"));
    }

    @Test
    void missingOptionalQueueValuesStayCapturedAndDoNotRequireCoreFields() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("queue-join-only")
        ));
        final DatadogRuntimeSnapshot sparseQueue = new DatadogRuntimeSnapshot(
                Instant.parse("2026-07-28T05:00:00Z"),
                Instant.parse("2026-07-28T05:00:00Z"),
                "i-queue",
                null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                List.of()
        );

        final RunEnvironmentMetadata metadata = RunEnvironmentMetadata.capture(
                request,
                ignored -> List.of(sparseQueue)
        );

        assertEquals("captured", metadata.captureStatus());
        assertTrue(metadata.captureWarnings().contains("queue: i-queue: not reported: machine.vcpu"));
        assertFalse(metadata.captureWarnings().stream().anyMatch(value -> value.contains("hikari")));
        assertFalse(metadata.captureWarnings().stream().anyMatch(value -> value.contains("oracle")));
        assertFalse(metadata.captureWarnings().stream().anyMatch(value -> value.contains("admission")));
    }

    @Test
    void oneFailedTargetMakesEndToEndCapturePartialWithoutDroppingSuccessfulTarget() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("ticket-open-end-to-end")
        ));
        final RunEnvironmentClient client = target -> {
            if (target.core()) {
                throw new IOException("Datadog unavailable");
            }
            return List.of(queueSnapshot("i-queue"));
        };

        final RunEnvironmentMetadata metadata = RunEnvironmentMetadata.capture(request, client);

        assertEquals("partial", metadata.captureStatus());
        assertEquals("captured", metadata.targets().get(0).captureStatus());
        assertEquals("failed", metadata.targets().get(1).captureStatus());
        assertEquals(1, metadata.targets().get(0).instances().size());
        assertTrue(metadata.targets().get(1).instances().isEmpty());
        assertTrue(metadata.captureError().contains("core: Datadog unavailable"));
    }

    @Test
    void failedIdentityCaptureDoesNotCreateFakeEmptyInstance() {
        final LoadTestRequest request = LoadTestRequest.fromForm(Map.of(
                "simulation", List.of("queue-join-only")
        ));

        final RunEnvironmentMetadata metadata = RunEnvironmentMetadata.capture(
                request,
                ignored -> List.of(DatadogRuntimeSnapshot.empty())
        );

        assertEquals("failed", metadata.captureStatus());
        assertEquals("failed", metadata.targets().getFirst().captureStatus());
        assertTrue(metadata.targets().getFirst().instances().isEmpty());
        assertTrue(metadata.captureError().contains("without a host identity"));
    }

    @Test
    void removesCredentialsQueryAndFragmentFromRecordedTargetUrl() {
        assertEquals(
                "https://queue.oneticket.site:8443/path",
                RunEnvironmentMetadata.sanitizeBaseUrl(
                        "https://user:password@queue.oneticket.site:8443/path?token=secret#fragment"
                )
        );
    }

    private static DatadogRuntimeSnapshot queueSnapshot(final String host) {
        return queueSnapshot(
                host,
                Instant.parse("2026-07-28T05:00:00Z"),
                4,
                8_589_934_592L,
                "a91b32f"
        );
    }

    private static DatadogRuntimeSnapshot queueSnapshot(
            final String host,
            final Instant observedAt,
            final Integer vcpu,
            final Long ramBytes,
            final String commit
    ) {
        return new DatadogRuntimeSnapshot(
                observedAt,
                observedAt.minusSeconds(1),
                host,
                "container-" + host,
                vcpu,
                ramBytes,
                2.0,
                4_294_967_296L,
                3_221_225_472L,
                "25.0.3_9-lts",
                commit,
                "leehusung/ticket-queue",
                "sha256:image",
                200,
                8192,
                null,
                4_294_967_296L,
                "private endpoint observed on application host (Datadog inference)",
                null,
                List.of()
        );
    }
}
