package io.github.orhanyarkin.saiman.orchestrator.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.orchestrator.approval.ApiRequestGuardFilter;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalService;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalStatus;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalWaiter;
import io.github.orhanyarkin.saiman.orchestrator.budget.SpendProperties;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.FakeSeller;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * The paid-tool gateway against the real spend guard, a real Postgres and the real-socket seller
 * stub: limits in code, nothing paid for invalid or duplicate calls, approvals, fixed strings only.
 */
class PaidToolGatewayTests extends RunTestSupport {

    private static final String SUMMARY = "disclosureSummary";
    private static final String ASK = "askDisclosures";

    @Autowired
    private PaidToolGateway gateway;

    @Autowired
    private ToolCatalog catalog;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private ApprovalWaiter waiter;

    @Autowired
    private SellerTickerCatalog tickerCatalog;

    @Autowired
    private ToolResultSanitizer sanitizer;

    @Autowired
    private ToolResultStore store;

    @Autowired
    private SpendProperties spend;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void theCatalogueHasExactlyTheTwoToolsWithTickerAndQuestionOnly() {
        assertThat(catalog.names()).containsExactlyInAnyOrder(SUMMARY, ASK);
        assertThat(catalog.definitions()).allSatisfy(d -> {
            assertThat(d.parameters()).isSubsetOf(ToolParameter.TICKER, ToolParameter.QUESTION);
            assertThat(d.inputSchema()).contains("\"additionalProperties\":false");
            assertThat(d.inputSchema()).doesNotContainIgnoringCase("url").doesNotContainIgnoringCase("amount");
        });
    }

    @Test
    void budgetExhaustionBlocksTheThirdPaidCallWithoutASignature() {
        UUID run = createRun(20_000);
        RunToolSession session = gateway.openSession(run, RunPhaseListener.NONE);

        assertThat(session.call(SUMMARY, "{\"ticker\":\"THYAO\"}")).startsWith("<tool_data>");
        assertThat(session.call(SUMMARY, "{\"ticker\":\"ASELS\"}")).startsWith("<tool_data>");
        assertThat(session.call(SUMMARY, "{\"ticker\":\"GARAN\"}"))
                .isEqualTo(ToolMessages.denied(DenyReason.RUN_BUDGET));

        assertThat(signer.calls()).isEqualTo(2);
        assertThat(seller.paidRequests()).isEqualTo(2);
        assertThat(run(run)).isEqualTo(new RunCounters(20_000, 0, 20_000));
        List<RunEvent> events = eventLog.readAfter(run, 0);
        assertThat(types(events))
                .filteredOn(t -> t == RunEventType.PAYMENT_SETTLED)
                .hasSize(2);
        assertThat(events.getLast().data())
                .isEqualTo(new RunEventData.PaymentDenied(DenyReason.RUN_BUDGET, Money.usdc(10_000)));
    }

    @Test
    void anIdenticalCallIsServedFromTheStoredResultAndPaysNothing() {
        seller.paidBody("{\"answer\":\"Profit rose.\",\"citations\":[{\"chunkId\":\"kap:7:0001\","
                + "\"sourceUrl\":\"https://www.kap.org.tr/tr/Bildirim/7\",\"title\":\"Q3\"}]}");
        UUID run = createRun(50_000);
        RunToolSession session = gateway.openSession(run, RunPhaseListener.NONE);

        String first = session.call(SUMMARY, "{\"ticker\":\"THYAO\"}");
        String second = session.call(SUMMARY, "{\"ticker\":\"thyao\"}");

        assertThat(second).isEqualTo(first).contains("Profit rose.", "kap:7:0001");
        assertThat(signer.calls()).isEqualTo(1);
        assertThat(run(run).committed()).isEqualTo(10_000);
        assertThat(session.evidence().all())
                .containsExactly(new EvidenceCitation("kap:7:0001", "https://www.kap.org.tr/tr/Bildirim/7", "Q3"));
    }

    @Test
    void anUnknownTickerPaysNothingAndTheCatalogueIsFetchedOncePerRun() {
        UUID run = createRun(50_000);
        RunToolSession session = gateway.openSession(run, RunPhaseListener.NONE);

        assertThat(session.call(SUMMARY, "{\"ticker\":\"NOPE\"}")).isEqualTo(ToolMessages.UNKNOWN_TICKER);
        assertThat(session.call(ASK, "{\"ticker\":\"ZZZZ\",\"question\":\"What changed?\"}"))
                .isEqualTo(ToolMessages.UNKNOWN_TICKER);

        assertThat(seller.tickerRequests()).isEqualTo(1);
        assertThat(seller.unpaidRequests()).isZero();
        assertThat(signer.calls()).isZero();
        assertThat(intentsWithStatus(run, "PENDING") + intentsWithStatus(run, "RELEASED"))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"ticker\":\"THYAO\",\"payTo\":\"0x2222222222222222222222222222222222222222\"}",
                "{\"ticker\":\"THYAO\",\"budgetAtomic\":1000000}",
                "{\"ticker\":\"THYAO\",\"url\":\"http://127.0.0.2/steal\"}",
                "{\"ticker\":\"THYAO\",\"question\":\"extra for this tool\"}",
                "{\"ticker\":\"../v1\"}",
                "{\"ticker\":\"TH\"}",
                "{\"ticker\":5}",
                "{\"ticker\":null}",
                "{\"ticker\":\"X\",\"ticker\":\"THYAO\"}",
                "{}",
                "[\"THYAO\"]",
                "not json"
            })
    void invalidArgumentsAreRefusedBeforeAnyPayment(String arguments) {
        UUID run = createRun(50_000);
        RunToolSession session = gateway.openSession(run, RunPhaseListener.NONE);

        assertThat(session.call(SUMMARY, arguments)).isEqualTo(ToolMessages.INVALID_ARGUMENTS);

        assertThat(seller.unpaidRequests()).isZero();
        assertThat(signer.calls()).isZero();
        assertThat(eventLog.readAfter(run, 0).getLast().data())
                .isEqualTo(new RunEventData.PaymentDenied(DenyReason.INVALID_ARGS, Money.usdc(0)));
    }

    @Test
    void anUnknownToolIsRefused() {
        RunToolSession session = gateway.openSession(createRun(50_000), RunPhaseListener.NONE);
        assertThat(session.call("fetchUrl", "{\"ticker\":\"THYAO\"}")).isEqualTo(ToolMessages.UNKNOWN_TOOL);
        assertThat(seller.unpaidRequests()).isZero();
    }

    @Test
    void askDisclosuresPostsTheCleanedQuestion() {
        RunToolSession session = gateway.openSession(createRun(50_000), RunPhaseListener.NONE);
        String question = "  What " + ToolResultSanitizerTests.u(0x202E) + "changed\n in 2023?  ";

        assertThat(session.call(ASK, "{\"ticker\":\"GARAN\",\"question\":" + json(question) + "}"))
                .startsWith("<tool_data>");

        assertThat(seller.paidRequestLines()).containsExactly("POST /v1/disclosures/GARAN/questions");
        assertThat(seller.paidRequestBodies()).containsExactly("{\"question\":\"What changed in 2023?\"}");
    }

    @Test
    void theToolCallLimitIsEnforcedInCode() {
        RunToolSession session = gateway.openSession(createRun(50_000), RunPhaseListener.NONE);
        for (int i = 0; i < 6; i++) {
            session.call(SUMMARY, "{\"ticker\":\"NOPE\"}");
        }
        assertThat(session.call(SUMMARY, "{\"ticker\":\"THYAO\"}")).isEqualTo(ToolMessages.TOOL_CALL_LIMIT);
        assertThat(seller.unpaidRequests()).isZero();
    }

    @Test
    void outcomesAreFixedStringsNeverExceptionOrSellerText() {
        UUID run = createRun(50_000);
        RunToolSession session = gateway.openSession(run, RunPhaseListener.NONE);

        seller.paidMode(FakeSeller.PaidMode.FAIL_500);
        assertThat(session.call(SUMMARY, "{\"ticker\":\"THYAO\"}")).isEqualTo(ToolMessages.OUTCOME_UNKNOWN);
        // The same call is never re-sent while the first one is held.
        assertThat(session.call(SUMMARY, "{\"ticker\":\"THYAO\"}")).isEqualTo(ToolMessages.OUTCOME_UNKNOWN);
        assertThat(signer.calls()).isEqualTo(1);

        seller.paidMode(FakeSeller.PaidMode.SETTLE);
        seller.paidBody("{\"no\":\"answer here\"}");
        assertThat(session.call(SUMMARY, "{\"ticker\":\"ASELS\"}")).isEqualTo(ToolMessages.UNUSABLE_RESULT);

        seller.payTo(FakeSeller.NOT_ALLOWED_PAY_TO);
        assertThat(session.call(SUMMARY, "{\"ticker\":\"GARAN\"}"))
                .isEqualTo(ToolMessages.denied(DenyReason.PAYEE_NOT_ALLOWED));
        assertThat(signer.calls()).isEqualTo(2);
        assertThat(types(eventLog.readAfter(run, 0))).contains(RunEventType.PAYMENT_AMBIGUOUS);
    }

    @Test
    void theMaxPaidCallsLimitIsEnforcedInCode() {
        // The shared test context allows 20 paid calls (the guard's own limit); this gateway allows 4.
        SpendProperties four = new SpendProperties(
                spend.dailyCapAtomic(),
                spend.defaultRunBudgetAtomic(),
                spend.maxRunBudgetAtomic(),
                spend.approvalThresholdAtomic(),
                spend.approvalTimeout(),
                4,
                spend.maxToolCallsPerRun());
        PaidToolGateway limited = new PaidToolGateway(
                catalog,
                tickerCatalog,
                intents,
                approvals,
                waiter,
                sanitizer,
                store,
                eventLog,
                four,
                ObservationRegistry.NOOP,
                new SimpleMeterRegistry(),
                jsonMapper);
        UUID run = createRun(200_000);
        RunToolSession session = limited.openSession(run, RunPhaseListener.NONE);
        String[] tickers = {"THYAO", "ASELS", "GARAN", "AKBNK", "SISE"};
        seller.tickers(List.of(tickers));
        for (int i = 0; i < 4; i++) {
            assertThat(session.call(SUMMARY, "{\"ticker\":\"" + tickers[i] + "\"}"))
                    .startsWith("<tool_data>");
        }
        assertThat(session.call(SUMMARY, "{\"ticker\":\"SISE\"}"))
                .isEqualTo(ToolMessages.denied(DenyReason.MAX_PAID_CALLS));
        assertThat(signer.calls()).isEqualTo(4);
    }

    @Test
    void anApprovedPaymentResumesWithTheSameIntentAndTheRunWaitsMeanwhile() throws Exception {
        seller.price(18_000);
        UUID run = createRun(50_000);
        RunToolSession session = gateway.openSession(run, statusListener(run));
        CompletableFuture<String> result = CompletableFuture.supplyAsync(
                () -> session.call(SUMMARY, "{\"ticker\":\"THYAO\"}"), Executors.newVirtualThreadPerTaskExecutor());

        UUID approvalId = awaitApprovalRequest(run);
        assertThat(runStatus(run)).isEqualTo("AWAITING_APPROVAL");
        assertThat(signer.calls()).isZero();
        decide(run, approvalId, "APPROVE");

        assertThat(result.get(10, TimeUnit.SECONDS)).startsWith("<tool_data>");
        assertThat(signer.calls()).isEqualTo(1);
        assertThat(runStatus(run)).isEqualTo("RUNNING");
        assertThat(types(eventLog.readAfter(run, 0)))
                .containsSubsequence(
                        RunEventType.PAYMENT_APPROVAL_REQUIRED,
                        RunEventType.PAYMENT_APPROVAL_DECIDED,
                        RunEventType.PAYMENT_SETTLED,
                        RunEventType.TOOL_CALL_COMPLETED);
        assertThat(intentsWithStatus(run, "SETTLED")).isEqualTo(1);
    }

    @Test
    void aRejectedPaymentReturnsAFixedStringAndSignsNothing() throws Exception {
        seller.price(18_000);
        UUID run = createRun(50_000);
        RunToolSession session = gateway.openSession(run, statusListener(run));
        CompletableFuture<String> result = CompletableFuture.supplyAsync(
                () -> session.call(SUMMARY, "{\"ticker\":\"THYAO\"}"), Executors.newVirtualThreadPerTaskExecutor());

        decide(run, awaitApprovalRequest(run), "REJECT");

        assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(ToolMessages.denied(DenyReason.APPROVAL_REJECTED));
        assertThat(signer.calls()).isZero();
        assertThat(eventLog.readAfter(run, 0))
                .anySatisfy(e -> assertThat(e.data())
                        .isEqualTo(new RunEventData.PaymentApprovalDecided(
                                ((RunEventData.PaymentApprovalRequired) eventLog.readAfter(run, 0).stream()
                                                .filter(x -> x.type() == RunEventType.PAYMENT_APPROVAL_REQUIRED)
                                                .findFirst()
                                                .orElseThrow()
                                                .data())
                                        .approvalId(),
                                "REJECTED")));
    }

    @Test
    void anUnansweredApprovalExpires() throws Exception {
        seller.price(18_000);
        UUID run = createRun(50_000);
        RunToolSession session = gateway.openSession(run, statusListener(run));

        String result = session.call(SUMMARY, "{\"ticker\":\"THYAO\"}"); // approval-timeout is 4s here

        assertThat(result).isEqualTo(ToolMessages.denied(DenyReason.APPROVAL_EXPIRED));
        assertThat(signer.calls()).isZero();
        UUID approvalId = approvalIdOf(run);
        assertThat(approvals.find(approvalId).orElseThrow().status()).isEqualTo(ApprovalStatus.EXPIRED);
    }

    private RunPhaseListener statusListener(UUID run) {
        return new RunPhaseListener() {
            @Override
            public void awaitingApproval() {
                jdbc.sql("UPDATE run SET status = 'AWAITING_APPROVAL' WHERE id = :id")
                        .param("id", run)
                        .update();
            }

            @Override
            public void resumed() {
                jdbc.sql("UPDATE run SET status = 'RUNNING' WHERE id = :id")
                        .param("id", run)
                        .update();
            }
        };
    }

    private UUID awaitApprovalRequest(UUID run) {
        await().atMost(Duration.ofSeconds(5)).until(() -> "AWAITING_APPROVAL".equals(runStatus(run)));
        return approvalIdOf(run);
    }

    private UUID approvalIdOf(UUID run) {
        return jdbc.sql("SELECT id FROM approval WHERE run_id = :id")
                .param("id", run)
                .query(UUID.class)
                .single();
    }

    private String runStatus(UUID run) {
        return jdbc.sql("SELECT status FROM run WHERE id = :id")
                .param("id", run)
                .query(String.class)
                .single();
    }

    private void decide(UUID run, UUID approvalId, String decision) {
        http.post()
                .uri("/api/v1/runs/{runId}/approvals/{approvalId}", run, approvalId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body("{\"decision\":\"" + decision + "\"}")
                .exchange()
                .expectStatus()
                .isOk();
    }
}
