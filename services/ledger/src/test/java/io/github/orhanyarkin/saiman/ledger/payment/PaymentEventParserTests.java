package io.github.orhanyarkin.saiman.ledger.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** {@link PaymentEventParser} without Spring: every out-of-range or forged input is malformed, never retryable. */
class PaymentEventParserTests {

    /** {@code TestPayment} events occur at 2026-10-01T10:00Z. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);

    private final JsonMapper json = JsonMapper.builder().build();
    private final PaymentEventParser parser = new PaymentEventParser(json, CLOCK);
    private final TestPayment payment = TestPayment.random(new SplittableRandom(), 20_000);

    @Test
    void validEventsParse() {
        var authorized = payment.authorized();
        var buyer = payment.buyerSettled();
        var seller = payment.sellerSettled();
        var failed = payment.sellerSettleFailed();

        assertThat(parser.authorized(json.writeValueAsString(authorized))).isEqualTo(authorized);
        assertThat(parser.settled(json.writeValueAsString(buyer))).isEqualTo(buyer);
        assertThat(parser.settled(json.writeValueAsString(seller))).isEqualTo(seller);
        assertThat(parser.failed(json.writeValueAsString(failed))).isEqualTo(failed);
    }

    @Test
    void validBeforeOfLongMaxIsMalformed() {
        String forged = json.writeValueAsString(payment.authorized())
                .replace("\"validBefore\":1790000060", "\"validBefore\":" + Long.MAX_VALUE);

        assertMalformed(() -> parser.authorized(forged));
    }

    @Test
    void nonUsdcAssetIsMalformed() {
        String forged = json.writeValueAsString(payment.buyerSettled())
                .replace(TestPayment.USDC_ADDRESS, "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238");

        assertMalformed(() -> parser.settled(forged));
    }

    @Test
    void amountAsAStringIsMalformed() {
        String forged = json.writeValueAsString(payment.authorized())
                .replace("\"atomicUnits\":20000", "\"atomicUnits\":\"20000\"");

        assertMalformed(() -> parser.authorized(forged));
    }

    @Test
    void duplicateKeyIsMalformed() {
        String valid = json.writeValueAsString(payment.authorized());
        String forged = valid.substring(0, valid.length() - 1) + ",\"payTo\":\"0x" + "2".repeat(40) + "\"}";

        assertMalformed(() -> parser.authorized(forged));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"2024-12-31T23:59:59Z\"", "\"2026-10-02T12:00:01Z\""})
    void occurredAtMissingOrOutOfRangeIsMalformed(String occurredAt) {
        String forged = json.writeValueAsString(payment.authorized())
                .replace("\"occurredAt\":\"2026-10-01T10:00:00Z\"", "\"occurredAt\":" + occurredAt);
        assertThat(forged).contains("\"occurredAt\":" + occurredAt);

        assertMalformed(() -> parser.authorized(forged));
    }

    @Test
    void producerIsBoundToTheBook() {
        String buyerFromSeller =
                json.writeValueAsString(payment.buyerSettled()).replace("\"orchestrator\"", "\"seller-api\"");
        String sellerFromBuyer =
                json.writeValueAsString(payment.sellerSettled()).replace("\"seller-api\"", "\"orchestrator\"");
        String authorizedFromStranger =
                json.writeValueAsString(payment.authorized()).replace("\"orchestrator\"", "\"evil\"");

        assertMalformed(() -> parser.settled(buyerFromSeller));
        assertMalformed(() -> parser.settled(sellerFromBuyer));
        assertMalformed(() -> parser.authorized(authorizedFromStranger));
    }

    private static void assertMalformed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(MalformedPaymentEventException.class);
    }
}
