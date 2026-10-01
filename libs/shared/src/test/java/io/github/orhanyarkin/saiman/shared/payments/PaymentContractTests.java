package io.github.orhanyarkin.saiman.shared.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.ledger.EntryPosted;
import io.github.orhanyarkin.saiman.shared.ledger.PostingLine;
import io.github.orhanyarkin.saiman.shared.ledger.Side;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentContractTests {

    private static final String USDC = "0x036CbD53842c5426634e7929541eC2318f3dCF7e";
    private static final String PAYER = "0xdD542d774e0c0E546d76721396C69e113A644795";
    private static final String NONCE = "0x" + "Ab".repeat(32);
    private static final EventMetadata META = new EventMetadata("e-1", Instant.EPOCH, "orchestrator", "run-1");

    private static AuthorizationRef auth() {
        return new AuthorizationRef("eip155:84532", USDC, PAYER, NONCE, 1_790_000_060L);
    }

    @Test
    void paymentKeyIsLowerCaseAndStable() {
        assertThat(auth().paymentKey())
                .isEqualTo(("eip155:84532:" + USDC + ":" + PAYER + ":" + NONCE).toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void onlyBaseSepoliaIsAccepted() {
        assertThatThrownBy(() -> new AuthorizationRef("eip155:8453", USDC, PAYER, NONCE, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settledWithoutTransactionNeedsChainEvidence() {
        assertThatThrownBy(() -> new PaymentSettled(
                        META,
                        auth(),
                        Money.usdc(10_000),
                        PAYER,
                        "r",
                        Book.BUYER,
                        null,
                        SettlementEvidence.FACILITATOR,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PaymentSettled(
                                META,
                                auth(),
                                Money.usdc(10_000),
                                PAYER,
                                "r",
                                Book.BUYER,
                                null,
                                SettlementEvidence.CHAIN,
                                null,
                                null)
                        .txHash())
                .isNull();
    }

    @Test
    void failureReasonIsABoundedCode() {
        assertThatThrownBy(() -> new PaymentFailed(
                        META,
                        auth(),
                        Money.usdc(10_000),
                        PAYER,
                        "r",
                        Book.SELLER,
                        Finality.AMBIGUOUS,
                        "Facilitator said: <b>no</b>",
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amountsMustBePositiveUsdc() {
        assertThatThrownBy(() -> new PaymentAuthorized(
                        META, auth(), Money.usdMicros(10), PAYER, "r", UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnbalancedEntryIsRejected() {
        var debit = new PostingLine("a", Side.DEBIT, Money.usdc(20_000));
        var credit = new PostingLine("b", Side.CREDIT, Money.usdc(19_999));
        assertThatThrownBy(() -> new EntryPosted(
                        META, UUID.randomUUID(), UUID.randomUUID(), "SALE", List.of(debit, credit), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forgedBoundsAreRejected() {
        assertThatThrownBy(() -> new AuthorizationRef("eip155:84532", USDC, PAYER, NONCE, Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthorizationRef("eip155:84532", PAYER, PAYER, NONCE, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAuthorized(
                        META,
                        auth(),
                        Money.usdc(9_007_199_254_740_992L),
                        PAYER,
                        "r",
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
