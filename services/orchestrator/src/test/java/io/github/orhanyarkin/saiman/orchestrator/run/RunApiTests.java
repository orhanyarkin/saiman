package io.github.orhanyarkin.saiman.orchestrator.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.approval.ApiRequestGuardFilter;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentApprovalRequiredException;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.SellerEndpoint;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolResultSanitizerTests;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;

/** {@code POST /api/v1/runs} admission and {@code GET /api/v1/runs/{id}}, plus the run lifecycle. */
class RunApiTests extends RunTestSupport {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PaymentIntentService paymentIntents;

    @Test
    void aRunIsAdmittedExecutedAndSummarised() {
        Map<String, Object> started = startRun("What did THYAO disclose in 2023?", null);
        UUID runId = runId(started);
        assertThat(started.get("eventsUrl")).isEqualTo("/api/v1/runs/" + runId + "/events");

        List<RunEvent> events = awaitTerminal(runId);

        assertThat(types(events)).containsExactly(RunEventType.RUN_STARTED, RunEventType.RUN_COMPLETED);
        assertThat(events.getFirst().data())
                .isEqualTo(new RunEventData.RunStarted("What did THYAO disclose in 2023?", Money.usdc(50_000)));
        http.get()
                .uri("/api/v1/runs/{id}", runId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("SUCCEEDED")
                .jsonPath("$.budget.atomicUnits")
                .isEqualTo(50_000)
                .jsonPath("$.budget.asset")
                .isEqualTo("USDC")
                .jsonPath("$.cost.totalUsd.atomicUnits")
                .isEqualTo(0)
                .jsonPath("$.report.answer")
                .isEqualTo(ScriptedPipeline_DEFAULT_ANSWER);
    }

    private static final String ScriptedPipeline_DEFAULT_ANSWER = "scripted answer";

    @Test
    void theQuestionIsCleanedOfControlAndBidiCharacters() {
        String hostile = "  What" + ToolResultSanitizerTests.u(0) + " is" + ToolResultSanitizerTests.u(0x202E)
                + " THYAO\n doing?  ";
        UUID runId = runId(startRun(hostile, 20_000L));
        awaitTerminal(runId);
        assertThat(runService.summary(runId).orElseThrow().question()).isEqualTo("What is THYAO doing?");
        assertThat(runService.summary(runId).orElseThrow().budget()).isEqualTo(Money.usdc(20_000));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"question\":\"ab\"}",
                "{\"question\":\"   \"}",
                "{}",
                "{\"question\":null}",
                "{\"question\":\"valid question\",\"budgetAtomic\":200001}",
                "{\"question\":\"valid question\",\"budgetAtomic\":0}",
                "{\"question\":\"valid question\",\"budgetAtomic\":-5}"
            })
    void invalidRunsAreRefusedWithProblemDetails(String body) {
        postRun(body)
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(jdbc.sql("SELECT count(*) FROM run").query(Integer.class).single())
                .isZero();
    }

    @Test
    void tooLongAndControlOnlyQuestionsAreRefused() {
        postRun("{\"question\":" + json("x".repeat(501)) + "}").expectStatus().isBadRequest();
        postRun("{\"question\":" + json(ToolResultSanitizerTests.u(1, 2, 3, 0x202E, 0x2066)) + "}")
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void theGuardHeadersAreRequired() {
        http.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"question\":\"valid question\"}")
                .exchange()
                .expectStatus()
                .isForbidden();
        http.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.TEXT_PLAIN)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body("{\"question\":\"valid question\"}")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void excessRunsGet429InsteadOfAQueue() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        pipeline.script(ctx -> {
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return io.github.orhanyarkin.saiman.orchestrator.run.RunOutcome.succeeded(
                    new RunEventData.Report("done", List.of()));
        });
        try {
            UUID first = runId(startRun("first question", null));
            UUID second = runId(startRun("second question", null));
            postRun("{\"question\":\"third question\"}")
                    .expectStatus()
                    .isEqualTo(429)
                    .expectHeader()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            release.countDown();
            awaitTerminal(first);
            awaitTerminal(second);
        } finally {
            release.countDown();
        }
        // Permits are back: a new run is admitted.
        awaitTerminal(runId(startRun("fourth question", null)));
    }

    @Test
    void runsAreRefusedUntilTheApplicationIsReady() {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        try {
            postRun("{\"question\":\"valid question\"}").expectStatus().isEqualTo(503);
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM run").query(Integer.class).single())
                .isZero();
    }

    @Test
    void anUnknownRunIs404() {
        http.get()
                .uri("/api/v1/runs/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void aPipelineExceptionFailsTheRunWithAFixedCodeOnly() {
        pipeline.script(ctx -> {
            throw new IllegalStateException("SECRET model text that must not leak");
        });
        UUID runId = runId(startRun("valid question", null));
        List<RunEvent> events = awaitTerminal(runId);

        assertThat(events.getLast().data())
                .isEqualTo(new RunEventData.RunFailed(
                        "INTERNAL_ERROR",
                        io.github.orhanyarkin.saiman.shared.run.RunCost.of(Money.usdc(0), Money.usdMicros(0))));
        http.get()
                .uri("/api/v1/runs/{id}", runId)
                .exchange()
                .expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain("SECRET").contains("INTERNAL_ERROR"));
    }

    @Test
    void aFailedRunClosesItsPendingApprovalsAndIntents() {
        seller.price(18_000);
        AtomicReference<UUID> approval = new AtomicReference<>();
        AtomicReference<UUID> pendingIntent = new AtomicReference<>();
        pipeline.script(ctx -> {
            PaymentIntentHandle awaiting = paymentIntents.create(
                    ctx.runId(),
                    "disclosureSummary",
                    "a",
                    SellerEndpoint.DISCLOSURE_SUMMARY,
                    Map.of("ticker", "THYAO"));
            try {
                client.send(awaiting, null);
            } catch (PaymentApprovalRequiredException e) {
                approval.set(e.approvalId());
            }
            pendingIntent.set(paymentIntents
                    .create(
                            ctx.runId(),
                            "disclosureSummary",
                            "b",
                            SellerEndpoint.DISCLOSURE_SUMMARY,
                            Map.of("ticker", "ASELS"))
                    .id());
            return RunOutcome.failed(FailureCode.NO_EVIDENCE);
        });
        UUID runId = runId(startRun("valid question", null));
        awaitTerminal(runId);

        assertThat(approval.get()).isNotNull();
        assertThat(jdbc.sql("SELECT status FROM approval WHERE id = :id")
                        .param("id", approval.get())
                        .query(String.class)
                        .single())
                .isEqualTo("EXPIRED");
        assertThat(intentsWithStatus(runId, "EXPIRED")).isEqualTo(1);
        assertThat(paymentIntents
                        .find(pendingIntent.get())
                        .orElseThrow()
                        .status()
                        .name())
                .isEqualTo("RELEASED");
        http.post()
                .uri("/api/v1/runs/{runId}/approvals/{approvalId}", runId, approval.get())
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body("{\"decision\":\"APPROVE\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        assertThat(signer.calls()).isZero();
        assertThat(runService.summary(runId).orElseThrow().failureCode()).isEqualTo("NO_EVIDENCE");
    }
}
