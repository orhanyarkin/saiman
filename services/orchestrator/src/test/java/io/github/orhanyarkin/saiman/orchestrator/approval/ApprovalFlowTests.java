package io.github.orhanyarkin.saiman.orchestrator.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.orchestrator.payment.PaidCallException;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentApprovalRequiredException;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentDeniedException;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.FakeSeller;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Approval above the threshold (15000 here; the seller asks 18000): nothing is signed before a
 * human approves, the approval only opens the threshold gate, and the fresh 402 must match it.
 */
class ApprovalFlowTests extends SpendTestSupport {

    private static final long ABOVE_THRESHOLD = 18_000;

    @Autowired
    private RestTestClient http;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private ApprovalWaiter waiter;

    @Test
    void aboveTheThresholdNothingIsSignedBeforeApproval() {
        UUID run = createRun(50_000);
        seller.price(ABOVE_THRESHOLD);
        PaymentIntentHandle handle = newIntent(run);

        UUID approvalId = requireApproval(handle);

        assertThat(signer.calls()).isZero();
        assertThat(seller.paidRequests()).isZero();
        assertThat(intents.find(handle.id()).orElseThrow().status()).isEqualTo(PaymentIntentStatus.AWAITING_APPROVAL);
        ApprovalView approval = approvals.find(approvalId).orElseThrow();
        assertThat(approval.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approval.amountAtomic()).isEqualTo(ABOVE_THRESHOLD);
        assertThat(approval.payTo()).isEqualTo(FakeSeller.PAY_TO.toLowerCase(Locale.ROOT));
        assertThat(approval.resource()).isEqualTo(handle.resource().toString());
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 0));
    }

    @Test
    void anApprovedIntentProceedsOnceWithoutRaisingTheBudget() throws Exception {
        UUID run = createRun(50_000);
        seller.price(ABOVE_THRESHOLD);
        PaymentIntentHandle handle = newIntent(run);
        UUID approvalId = requireApproval(handle);

        CompletableFuture<ApprovalStatus> waiting = CompletableFuture.supplyAsync(
                () -> waiter.await(approvalId, Duration.ofSeconds(30)), Executors.newVirtualThreadPerTaskExecutor());
        decide(run, approvalId, "APPROVE")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("APPROVED");
        assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(ApprovalStatus.APPROVED);

        assertThat(client.send(handle, null).paid()).isTrue();

        assertThat(signer.calls()).isEqualTo(1);
        assertThat(intents.find(handle.id()).orElseThrow().status()).isEqualTo(PaymentIntentStatus.SETTLED);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, ABOVE_THRESHOLD));
    }

    @Test
    void theFreshOfferMustMatchTheApprovedAmount() {
        UUID run = createRun(50_000);
        seller.price(ABOVE_THRESHOLD);
        PaymentIntentHandle handle = newIntent(run);
        decide(run, requireApproval(handle), "APPROVE").expectStatus().isOk();

        seller.price(ABOVE_THRESHOLD + 1);
        assertThatThrownBy(() -> client.send(handle, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.APPROVAL_MISMATCH));
        assertThat(intents.find(handle.id()).orElseThrow().denyReason()).isEqualTo(DenyReason.APPROVAL_MISMATCH);
        assertThat(signer.calls()).isZero();
        assertThat(seller.paidRequests()).isZero();
    }

    @Test
    void theFreshOfferMustMatchTheApprovedPayee() {
        UUID run = createRun(50_000);
        seller.price(ABOVE_THRESHOLD);
        PaymentIntentHandle handle = newIntent(run);
        decide(run, requireApproval(handle), "APPROVE").expectStatus().isOk();

        seller.payTo(FakeSeller.PAY_TO_2); // allowlisted, but not what the human approved
        assertThatThrownBy(() -> client.send(handle, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.APPROVAL_MISMATCH));
        assertThat(signer.calls()).isZero();
    }

    @Test
    void aRejectedApprovalNeverSigns() {
        UUID run = createRun(50_000);
        seller.price(ABOVE_THRESHOLD);
        PaymentIntentHandle handle = newIntent(run);
        UUID approvalId = requireApproval(handle);

        decide(run, approvalId, "REJECT")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("REJECTED");
        assertThat(waiter.await(approvalId, Duration.ofSeconds(5))).isEqualTo(ApprovalStatus.REJECTED);

        assertThatThrownBy(() -> client.send(handle, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.APPROVAL_REJECTED));
        assertThat(signer.calls()).isZero();
        assertThat(intents.find(handle.id()).orElseThrow().status()).isEqualTo(PaymentIntentStatus.REJECTED);
    }

    @Test
    void anExpiredApprovalNeverSignsAndCannotBeApprovedLater() {
        UUID run = createRun(50_000);
        seller.price(ABOVE_THRESHOLD);
        PaymentIntentHandle handle = newIntent(run);
        UUID approvalId = requireApproval(handle);

        assertThat(waiter.await(approvalId, Duration.ofMillis(100))).isEqualTo(ApprovalStatus.EXPIRED);

        decide(run, approvalId, "APPROVE").expectStatus().isEqualTo(409);
        assertThatThrownBy(() -> client.send(handle, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.APPROVAL_EXPIRED));
        assertThat(signer.calls()).isZero();
        assertThat(approvals.find(approvalId).orElseThrow().status()).isEqualTo(ApprovalStatus.EXPIRED);
    }

    @Test
    void anApprovalNeverRaisesTheRunBudget() {
        UUID run = createRun(30_000);
        seller.price(ABOVE_THRESHOLD);
        PaymentIntentHandle first = newIntent(run);
        decide(run, requireApproval(first), "APPROVE").expectStatus().isOk();
        assertThat(client.send(first, null).paid()).isTrue();

        // 18000 + 18000 > 30000: refused on the budget, no approval is even requested.
        PaymentIntentHandle second = newIntent(run);
        assertThatThrownBy(() -> client.send(second, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.RUN_BUDGET));
        assertThat(approvals.findForIntent(second.id())).isEmpty();
        assertThat(signer.calls()).isEqualTo(1);
        assertThat(run(run)).isEqualTo(new RunCounters(30_000, 0, ABOVE_THRESHOLD));
    }

    @Test
    void aPaymentOverTheRunBudgetIsDeniedWithoutAskingForApproval() {
        UUID run = createRun(10_000);
        seller.price(ABOVE_THRESHOLD);
        PaymentIntentHandle handle = newIntent(run);

        assertThatThrownBy(() -> client.send(handle, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.RUN_BUDGET));
        assertThat(approvals.findForIntent(handle.id())).isEmpty();
    }

    @Test
    void theEndpointAnswersWithProblemDetails() {
        UUID run = createRun(50_000);
        seller.price(ABOVE_THRESHOLD);
        UUID approvalId = requireApproval(newIntent(run));

        decide(UUID.randomUUID(), approvalId, "APPROVE")
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        decide(run, UUID.randomUUID(), "APPROVE").expectStatus().isNotFound();
        decide(run, approvalId, "MAYBE").expectStatus().isBadRequest();
        http.post()
                .uri("/api/v1/runs/{runId}/approvals/{approvalId}", run, approvalId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"decision\":\"APPROVE\"}")
                .exchange()
                .expectStatus()
                .isForbidden();
        http.post()
                .uri("/api/v1/runs/{runId}/approvals/{approvalId}", run, approvalId)
                .contentType(MediaType.TEXT_PLAIN)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body("{\"decision\":\"APPROVE\"}")
                .exchange()
                .expectStatus()
                .isForbidden();

        decide(run, approvalId, "APPROVE").expectStatus().isOk();
        decide(run, approvalId, "REJECT").expectStatus().isEqualTo(409);
        assertThat(approvals.find(approvalId).orElseThrow().status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    private UUID requireApproval(PaymentIntentHandle handle) {
        try {
            client.send(handle, null);
        } catch (PaymentApprovalRequiredException e) {
            return e.approvalId();
        } catch (PaidCallException e) {
            throw new AssertionError("expected an approval request, got " + e.getMessage(), e);
        }
        throw new AssertionError("expected an approval request, but the call was paid");
    }

    private RestTestClient.ResponseSpec decide(UUID run, UUID approvalId, String decision) {
        return http.post()
                .uri("/api/v1/runs/{runId}/approvals/{approvalId}", run, approvalId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body("{\"decision\":\"" + decision + "\"}")
                .exchange();
    }
}
