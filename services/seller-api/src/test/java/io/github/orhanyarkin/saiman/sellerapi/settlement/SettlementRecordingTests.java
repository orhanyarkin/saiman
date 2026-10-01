package io.github.orhanyarkin.saiman.sellerapi.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.SettlementTestBase;
import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.server.X402PaymentSettledEvent;
import io.github.orhanyarkin.x402.testing.TestWallets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;

/** The seller records each settlement outcome once and publishes it through the outbox (ADR-0016). */
class SettlementRecordingTests extends SettlementTestBase {

    @Autowired
    private ApplicationEventPublisher publisher;

    @Test
    void aSettledPaidCallWritesOneRowAndOnePublication() {
        PaymentPayload payload = newPayload();
        Eip3009Authorization authorization = payload.payload().authorization();

        getSummary(payload).expectStatus().isOk();

        Map<String, Object> row = jdbc.sql("SELECT * FROM settlement").query().singleRow();
        String key = ("eip155:84532:" + offer().asset() + ":" + authorization.from() + ":" + authorization.nonce())
                .toLowerCase(Locale.ROOT);
        assertThat(row.get("payment_key")).isEqualTo(key);
        assertThat(row.get("outcome")).isEqualTo("SETTLED");
        assertThat(row.get("amount_atomic")).isEqualTo(10_000L);
        assertThat((String) row.get("tx_hash")).matches("0x[0-9a-f]{64}");
        assertThat(row.get("pay_to")).isEqualTo(PAY_TO);

        assertThat(publications()).hasSize(1);
        Map.Entry<String, JsonNode> published = publications().get(0);
        assertThat(published.getKey()).isEqualTo("PaymentSettled");
        JsonNode event = published.getValue();
        assertThat(event.at("/book").asString()).isEqualTo("SELLER");
        assertThat(event.at("/evidence").asString()).isEqualTo("FACILITATOR");
        assertThat(event.at("/txHash").asString()).isEqualTo(row.get("tx_hash"));
        assertThat(event.at("/amount/atomicUnits").asLong()).isEqualTo(10_000L);
        assertThat(event.at("/meta/producer").asString()).isEqualTo("seller-api");
        assertThat(event.at("/meta/eventId").asString())
                .isEqualTo(SettlementRecorder.eventId(key, "SETTLED").toString());
        assertThat(event.at("/meta/correlationId").asString()).isNotBlank().doesNotContain(authorization.nonce());
        assertThat(event.at("/resource").asString()).isEqualTo("/v1/disclosures/THYAO/summary");
        assertThat(event.at("/authorization/payer").asString()).isEqualToIgnoringCase(authorization.from());
    }

    @Test
    void aSettleFailureWritesSettleFailedAndAnAmbiguousPaymentFailed() {
        FACILITATOR.injectSettleFailure("insufficient_funds");

        getSummary(newPayload()).expectStatus().isEqualTo(402);

        Map<String, Object> row = jdbc.sql("SELECT * FROM settlement").query().singleRow();
        assertThat(row.get("outcome")).isEqualTo("SETTLE_FAILED");
        assertThat(row.get("reason_code")).isEqualTo("insufficient_funds");
        assertThat(row.get("tx_hash")).isNull();

        assertThat(publications()).hasSize(1);
        assertThat(publications().get(0).getKey()).isEqualTo("PaymentFailed");
        JsonNode event = publications().get(0).getValue();
        assertThat(event.at("/book").asString()).isEqualTo("SELLER");
        assertThat(event.at("/finality").asString()).isEqualTo("AMBIGUOUS");
        assertThat(event.at("/reasonCode").asString()).isEqualTo("insufficient_funds");
    }

    @Test
    void theSameAuthorizationReportedTwiceYieldsOneRowAndOneEvent() {
        PaymentPayload payload = newPayload();
        Eip3009Authorization authorization = payload.payload().authorization();
        X402PaymentSettledEvent report = new X402PaymentSettledEvent(
                UUID.randomUUID(),
                "/v1/disclosures/THYAO/summary",
                offer(),
                authorization.from(),
                authorization.nonce(),
                authorization.value(),
                authorization.validBefore(),
                authorization.from(),
                "0x" + "ab".repeat(32),
                Instant.now());

        publisher.publishEvent(report);
        publisher.publishEvent(new X402PaymentSettledEvent(
                UUID.randomUUID(),
                report.resourceUrl(),
                report.requirements(),
                report.from(),
                report.nonce(),
                report.value(),
                report.validBefore(),
                report.payer(),
                report.transactionHash(),
                Instant.now()));

        assertThat(settlementRows()).isEqualTo(1);
        assertThat(publications()).hasSize(1);
        assertThat(TestWallets.PAYER.address()).isEqualToIgnoringCase(authorization.from());
    }
}
