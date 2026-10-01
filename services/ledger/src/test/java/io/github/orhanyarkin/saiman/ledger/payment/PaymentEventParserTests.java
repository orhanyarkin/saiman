package io.github.orhanyarkin.saiman.ledger.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** {@link PaymentEventParser} without Spring: every out-of-range or forged input is malformed, never retryable. */
class PaymentEventParserTests {

    private final JsonMapper json = JsonMapper.builder().build();
    private final PaymentEventParser parser = new PaymentEventParser(json);
    private final TestPayment payment = TestPayment.random(new SplittableRandom(), 20_000);

    @Test
    void validEventParses() {
        var event = payment.authorized();
        assertThat(parser.authorized(json.writeValueAsString(event))).isEqualTo(event);
    }

    @Test
    void validBeforeOfLongMaxIsMalformed() {
        String forged = json.writeValueAsString(payment.authorized())
                .replace("\"validBefore\":1790000060", "\"validBefore\":" + Long.MAX_VALUE);

        assertThatThrownBy(() -> parser.authorized(forged)).isInstanceOf(MalformedPaymentEventException.class);
    }

    @Test
    void nonUsdcAssetIsMalformed() {
        String forged = json.writeValueAsString(payment.buyerSettled())
                .replace(TestPayment.USDC_ADDRESS, "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238");

        assertThatThrownBy(() -> parser.settled(forged)).isInstanceOf(MalformedPaymentEventException.class);
    }
}
