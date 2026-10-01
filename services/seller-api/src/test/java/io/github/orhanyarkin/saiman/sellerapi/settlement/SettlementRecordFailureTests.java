package io.github.orhanyarkin.saiman.sellerapi.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.SettlementTestBase;
import io.github.orhanyarkin.saiman.shared.payments.CreditNoteIssued;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A failure while recording must not change the paid response (the settlement already happened on chain) and must
 * leave nothing half-written: row and publication commit or roll back together.
 */
@Import(SettlementRecordFailureTests.FailingListener.class)
class SettlementRecordFailureTests extends SettlementTestBase {

    static final AtomicBoolean PUBLISH_FAILS = new AtomicBoolean();
    static final AtomicBoolean CREDIT_NOTE_PUBLISH_FAILS = new AtomicBoolean();

    /** Stands in for a failing registry write: throws while the recorder's transaction is open. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingListener {
        @Bean
        PublishFailure publishFailure() {
            return new PublishFailure();
        }
    }

    static class PublishFailure {
        @EventListener
        void on(PaymentSettled event) {
            if (PUBLISH_FAILS.get()) {
                throw new IllegalStateException("publication failed");
            }
        }

        @EventListener
        void on(CreditNoteIssued event) {
            if (CREDIT_NOTE_PUBLISH_FAILS.get()) {
                throw new IllegalStateException("publication failed");
            }
        }
    }

    @MockitoSpyBean
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MeterRegistry meters;

    @AfterEach
    void restore() {
        PUBLISH_FAILS.set(false);
        CREDIT_NOTE_PUBLISH_FAILS.set(false);
        reset(transactionTemplate);
    }

    private double failures() {
        return meters.counter("saiman.seller.settlement_record_failures").count();
    }

    private double creditNoteFailures() {
        return meters.counter("saiman.seller.credit_note_record_failures").count();
    }

    private RestTestClient.ResponseSpec getUnknownTicker() {
        return client.get()
                .uri("/v1/disclosures/ZZZZZZ/summary")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, newPayload()))
                .exchange();
    }

    @Test
    void aFailedCreditNotePublicationRollsBackTheCreditNoteAndThePaidErrorIsUnchanged() {
        CREDIT_NOTE_PUBLISH_FAILS.set(true);
        double before = creditNoteFailures();

        getUnknownTicker()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .exists(X402Headers.PAYMENT_RESPONSE)
                .expectHeader()
                .contentType("application/problem+json");

        assertThat(creditNoteRows()).isZero();
        // The settlement, recorded at settle time in its own transaction, is unaffected.
        assertThat(settlementRows()).isEqualTo(1);
        assertThat(publications().stream().map(Map.Entry::getKey)).containsExactly("PaymentSettled");
        assertThat(creditNoteFailures()).isEqualTo(before + 1);
    }

    @Test
    void aDatabaseOutageDoesNotChangeThePaidErrorAndBothRecordersCountIt() {
        // executeWithoutResult (settlement recorder) delegates to execute (credit-note recorder).
        doThrow(new CannotCreateTransactionException("database down"))
                .when(transactionTemplate)
                .execute(any());
        double settlementBefore = failures();
        double creditNoteBefore = creditNoteFailures();

        getUnknownTicker().expectStatus().isNotFound().expectHeader().exists(X402Headers.PAYMENT_RESPONSE);

        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(settlementRows()).isZero();
        assertThat(creditNoteRows()).isZero();
        assertThat(failures()).isEqualTo(settlementBefore + 1);
        assertThat(creditNoteFailures()).isEqualTo(creditNoteBefore + 1);
    }

    @Test
    void aFailedPublicationRollsBackTheRowAndTheResponseIsUnchanged() {
        PUBLISH_FAILS.set(true);
        double before = failures();

        getSummary(newPayload())
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists(X402Headers.PAYMENT_RESPONSE)
                .expectBody()
                .jsonPath("$.ticker")
                .isEqualTo("THYAO");

        assertThat(settlementRows()).isZero();
        assertThat(publications()).isEmpty();
        assertThat(failures()).isEqualTo(before + 1);
    }

    @Test
    void aDatabaseOutageDoesNotChangeThePaidResponseAndIsCounted() {
        doThrow(new CannotCreateTransactionException("database down"))
                .when(transactionTemplate)
                .executeWithoutResult(any());
        double before = failures();

        getSummary(newPayload())
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists(X402Headers.PAYMENT_RESPONSE)
                .expectBody()
                .jsonPath("$.ticker")
                .isEqualTo("THYAO");

        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(settlementRows()).isZero();
        assertThat(failures()).isEqualTo(before + 1);
    }
}
