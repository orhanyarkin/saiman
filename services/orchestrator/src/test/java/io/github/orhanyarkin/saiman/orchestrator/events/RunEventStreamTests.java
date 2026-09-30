package io.github.orhanyarkin.saiman.orchestrator.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.AgentStep;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The event log: gap-free seqs under concurrency, SSE replay/live/terminal/heartbeat, JSON export. */
class RunEventStreamTests extends RunTestSupport {

    @Autowired
    private RunEventBus bus;

    @Autowired
    private JsonMapper json;

    @Test
    void seqsAreGapFreeUnder16ConcurrentAppenders() throws Exception {
        UUID run = createRun(50_000);
        int threads = 16;
        int each = 20;
        CountDownLatch start = new CountDownLatch(1);
        Set<Integer> seqs = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(CompletableFuture.runAsync(
                        () -> {
                            try {
                                start.await();
                            } catch (InterruptedException e) {
                                throw new IllegalStateException(e);
                            }
                            for (int i = 0; i < each; i++) {
                                seqs.add(eventLog.append(run, RunEventType.STEP_STARTED, step())
                                        .seq());
                            }
                        },
                        pool));
            }
            start.countDown();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
        }
        int total = threads * each;
        assertThat(seqs).hasSize(total);
        List<Integer> stored =
                eventLog.readAfter(run, 0).stream().map(RunEvent::seq).toList();
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            expected.add(i);
        }
        assertThat(stored).isEqualTo(expected);
    }

    @Test
    void replaysAfterLastEventIdThenCompletesOnTheTerminalEvent() {
        UUID run = createRun(50_000);
        for (int i = 0; i < 4; i++) {
            eventLog.append(run, RunEventType.STEP_STARTED, step());
        }
        eventLog.append(run, RunEventType.RUN_FAILED, failed());

        List<Sse> events = parse(stream(run, "2"));

        assertThat(events).extracting(Sse::id).containsExactly("3", "4", "5");
        assertThat(events).extracting(Sse::event).containsExactly("STEP_STARTED", "STEP_STARTED", "RUN_FAILED");
        JsonNode envelope = json.readTree(events.getFirst().data());
        assertThat(envelope.get("eventId").asString()).isEqualTo(run + ":3");
        assertThat(envelope.get("data").get("step").asString()).isEqualTo("PLANNER");
    }

    @Test
    void tailsLiveEventsWithHeartbeatsUntilTheTerminalEvent() throws Exception {
        UUID run = createRun(50_000);
        eventLog.append(run, RunEventType.STEP_STARTED, step());
        CompletableFuture<String> body =
                CompletableFuture.supplyAsync(() -> stream(run, null), Executors.newVirtualThreadPerTaskExecutor());
        await().atMost(Duration.ofSeconds(5)).until(() -> bus.subscriberCount(run) == 1);

        Thread.sleep(500); // at least one 200 ms heartbeat while idle
        eventLog.append(run, RunEventType.STEP_COMPLETED, step());
        eventLog.append(run, RunEventType.RUN_FAILED, failed());

        String raw = body.get(10, TimeUnit.SECONDS);
        assertThat(raw).contains(":heartbeat");
        assertThat(parse(raw)).extracting(Sse::id).containsExactly("1", "2", "3");
        await().atMost(Duration.ofSeconds(5)).until(() -> bus.subscriberCount(run) == 0);
    }

    @Test
    void theJsonExportHasTheSameEnvelopesAsTheStream() {
        UUID run = createRun(50_000);
        eventLog.append(run, RunEventType.STEP_STARTED, step());
        eventLog.append(run, RunEventType.RUN_FAILED, failed());

        List<String> streamed = parse(stream(run, null)).stream().map(Sse::data).toList();
        String exported = http.get()
                .uri("/api/v1/runs/{id}/events", run)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        List<JsonNode> exportedNodes = new ArrayList<>();
        json.readTree(exported).forEach(exportedNodes::add);
        assertThat(exportedNodes)
                .isEqualTo(streamed.stream().map(json::readTree).toList());
        assertThat(exported).doesNotContainIgnoringCase("idempotency").doesNotContainIgnoringCase("nonce");
    }

    @Test
    void unknownRunsAre404AndABadLastEventIdIs400() {
        http.get()
                .uri("/api/v1/runs/{id}/events", UUID.randomUUID())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isNotFound();
        http.get()
                .uri("/api/v1/runs/{id}/events", UUID.randomUUID())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        UUID run = createRun(50_000);
        http.get()
                .uri("/api/v1/runs/{id}/events", run)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Last-Event-ID", "abc")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    private String stream(UUID run, String lastEventId) {
        var request = http.get().uri("/api/v1/runs/{id}/events", run).accept(MediaType.TEXT_EVENT_STREAM);
        if (lastEventId != null) {
            request = request.header("Last-Event-ID", lastEventId);
        }
        return request.exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private static RunEventData step() {
        return new RunEventData.StepChanged(AgentStep.PLANNER);
    }

    private static RunEventData failed() {
        return new RunEventData.RunFailed("INTERNAL_ERROR", RunCost.of(Money.usdc(0), Money.usdMicros(0)));
    }

    record Sse(String id, String event, String data) {}

    private static List<Sse> parse(String raw) {
        List<Sse> events = new ArrayList<>();
        for (String block : raw.split("\n\n")) {
            String id = null;
            String event = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("id:")) {
                    id = line.substring(3).strip();
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                }
            }
            if (id != null) {
                events.add(new Sse(id, event, data.toString()));
            }
        }
        Set<String> ids = new HashSet<>();
        events.forEach(
                e -> assertThat(ids.add(e.id())).as("duplicate id " + e.id()).isTrue());
        return events;
    }
}
