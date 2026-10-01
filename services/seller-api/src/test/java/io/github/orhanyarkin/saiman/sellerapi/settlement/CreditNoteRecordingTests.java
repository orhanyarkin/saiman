package io.github.orhanyarkin.saiman.sellerapi.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.SettlementTestBase;
import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.server.X402PaidRequestFailedEvent;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;

/**
 * Upfront flow (ADR-0021): a paid request that is not served gets one {@code credit_note} row and one {@code
 * CreditNoteIssued} publication, next to the settlement recorded at settle time; a served one gets none.
 */
class CreditNoteRecordingTests extends SettlementTestBase {

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private MeterRegistry meters;

    private double creditNotes(String reason) {
        return meters.counter("saiman.seller.credit_notes", "reason", reason).count();
    }

    private double creditedAmount() {
        return meters.counter("saiman.seller.credit_note.amount").count();
    }

    private String key(Eip3009Authorization authorization) {
        return ("eip155:84532:" + offer().asset() + ":" + authorization.from() + ":" + authorization.nonce())
                .toLowerCase(Locale.ROOT);
    }

    @Test
    void anUnknownTickerIsSettledUpFrontThenCreditedInFull() {
        PaymentPayload payload = newPayload();
        Eip3009Authorization authorization = payload.payload().authorization();
        double notesBefore = creditNotes("handler_client_error");
        double amountBefore = creditedAmount();

        client.get()
                .uri("/v1/disclosures/ZZZZZZ/summary")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .exists(X402Headers.PAYMENT_RESPONSE);

        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        Map<String, Object> settlement =
                jdbc.sql("SELECT * FROM settlement").query().singleRow();
        assertThat(settlement.get("outcome")).isEqualTo("SETTLED");
        Map<String, Object> note = jdbc.sql("SELECT * FROM credit_note").query().singleRow();
        assertThat(note.get("payment_key")).isEqualTo(key(authorization));
        assertThat(note.get("tx_hash")).isEqualTo(settlement.get("tx_hash"));
        assertThat(note.get("amount_atomic")).isEqualTo(10_000L);
        assertThat(note.get("pay_to")).isEqualTo(PAY_TO);
        assertThat((String) note.get("payer")).isEqualToIgnoringCase(authorization.from());
        assertThat(note.get("http_status")).isEqualTo(404);
        assertThat(note.get("reason_code")).isEqualTo("handler_client_error");

        assertThat(publications().stream().map(Map.Entry::getKey))
                .containsExactly("PaymentSettled", "CreditNoteIssued");
        JsonNode event = publications().get(1).getValue();
        assertThat(event.at("/meta/eventId").asString())
                .isEqualTo(SettlementRecorder.eventId(key(authorization), "CREDIT_NOTE")
                        .toString());
        assertThat(event.at("/meta/producer").asString()).isEqualTo("seller-api");
        assertThat(event.at("/meta/correlationId").asString()).isNotBlank().doesNotContain(authorization.nonce());
        assertThat(event.at("/book").asString()).isEqualTo("SELLER");
        assertThat(event.at("/txHash").asString()).isEqualTo(settlement.get("tx_hash"));
        assertThat(event.at("/amount/atomicUnits").asLong()).isEqualTo(10_000L);
        assertThat(event.at("/httpStatus").asInt()).isEqualTo(404);
        assertThat(event.at("/reasonCode").asString()).isEqualTo("handler_client_error");
        assertThat(event.at("/resource").asString()).isEqualTo("/v1/disclosures/ZZZZZZ/summary");

        assertThat(creditNotes("handler_client_error")).isEqualTo(notesBefore + 1);
        assertThat(creditedAmount()).isEqualTo(amountBefore + 10_000);
    }

    @Test
    void aServedUpfrontRequestSettlesOnceAndIssuesNoCreditNote() {
        getSummary(newPayload())
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists(X402Headers.PAYMENT_RESPONSE)
                .expectBody()
                .jsonPath("$.ticker")
                .isEqualTo("THYAO");

        assertThat(FACILITATOR.verifyCallCount()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(settlementRows()).isEqualTo(1);
        assertThat(creditNoteRows()).isZero();
        assertThat(publications().stream().map(Map.Entry::getKey)).containsExactly("PaymentSettled");
    }

    @Test
    void theSamePaidFailureReportedTwiceYieldsOneRowAndOnePublication() {
        Eip3009Authorization a = newPayload().payload().authorization();
        double before = creditNotes("handler_server_error");
        X402PaidRequestFailedEvent report = report(a, UUID.randomUUID());

        publisher.publishEvent(report);
        publisher.publishEvent(report(a, UUID.randomUUID())); // another starter event id, same authorization

        assertThat(creditNoteRows()).isEqualTo(1);
        assertThat(publications()).hasSize(1);
        assertThat(publications().get(0).getKey()).isEqualTo("CreditNoteIssued");
        assertThat(creditNotes("handler_server_error")).isEqualTo(before + 1);
    }

    @Test
    void anInvalidReportStoresNothingAndIsCounted() {
        Eip3009Authorization a = newPayload().payload().authorization();
        double failures =
                meters.counter("saiman.seller.credit_note_record_failures").count();
        X402PaidRequestFailedEvent valid = report(a, UUID.randomUUID());

        publisher.publishEvent(new X402PaidRequestFailedEvent(
                valid.eventId(),
                valid.resourceUrl(),
                valid.requirements(),
                valid.from(),
                valid.nonce(),
                valid.value(),
                valid.validBefore(),
                valid.payer(),
                "not-a-hash",
                valid.httpStatus(),
                valid.reasonCode(),
                valid.failedAt()));

        assertThat(creditNoteRows()).isZero();
        assertThat(publications()).isEmpty();
        assertThat(meters.counter("saiman.seller.credit_note_record_failures").count())
                .isEqualTo(failures + 1);
    }

    private X402PaidRequestFailedEvent report(Eip3009Authorization a, UUID eventId) {
        return new X402PaidRequestFailedEvent(
                eventId,
                "/v1/disclosures/THYAO/summary",
                offer(),
                a.from(),
                a.nonce(),
                a.value(),
                a.validBefore(),
                a.from(),
                "0x" + "ef".repeat(32),
                503,
                X402PaidRequestFailedEvent.HANDLER_SERVER_ERROR,
                Instant.now());
    }
}
