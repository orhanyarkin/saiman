package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.payments.AuthorizationRef;
import io.github.orhanyarkin.saiman.shared.payments.Book;
import io.github.orhanyarkin.saiman.shared.payments.Finality;
import io.github.orhanyarkin.saiman.shared.payments.PaymentAuthorized;
import io.github.orhanyarkin.saiman.shared.payments.PaymentFailed;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import io.github.orhanyarkin.saiman.shared.payments.SettlementEvidence;
import java.time.Instant;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * One authorization and the events producers could report about it. Every factory call mints a new event id (a
 * redelivery is the same object twice). Addresses use mixed case on purpose: the ledger lower-cases them.
 */
public record TestPayment(AuthorizationRef authorization, Money amount, String payTo, UUID intentId, UUID runId) {

    public static final String USDC_ADDRESS = "0x036CbD53842c5426634e7929541eC2318f3dCF7e";
    private static final String RESOURCE = "http://seller-api:8081/v1/disclosures/ASELS/summary";
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    public static TestPayment random(SplittableRandom random, long amountAtomic) {
        return of(random, address(random), address(random), amountAtomic);
    }

    public static TestPayment of(SplittableRandom random, String payer, String payTo, long amountAtomic) {
        return new TestPayment(
                new AuthorizationRef(
                        AuthorizationRef.BASE_SEPOLIA, USDC_ADDRESS, payer, hex(random, 64), 1_790_000_060L),
                Money.usdc(amountAtomic),
                payTo,
                new UUID(random.nextLong(), random.nextLong()),
                new UUID(random.nextLong(), random.nextLong()));
    }

    public PaymentAuthorized authorized() {
        return new PaymentAuthorized(meta("orchestrator"), authorization, amount, payTo, RESOURCE, intentId, runId);
    }

    public PaymentSettled buyerSettled() {
        return new PaymentSettled(
                meta("orchestrator"),
                authorization,
                amount,
                payTo,
                RESOURCE,
                Book.BUYER,
                txHash(),
                SettlementEvidence.FACILITATOR,
                intentId,
                runId);
    }

    public PaymentSettled sellerSettled() {
        return new PaymentSettled(
                meta("seller-api"),
                authorization,
                amount,
                payTo,
                RESOURCE,
                Book.SELLER,
                txHash(),
                SettlementEvidence.FACILITATOR,
                null,
                null);
    }

    public PaymentFailed buyerExpiredUnused() {
        return new PaymentFailed(
                meta("orchestrator"),
                authorization,
                amount,
                payTo,
                RESOURCE,
                Book.BUYER,
                Finality.FINAL,
                "expired_unused",
                intentId,
                runId);
    }

    public PaymentFailed sellerSettleFailed() {
        return new PaymentFailed(
                meta("seller-api"),
                authorization,
                amount,
                payTo,
                RESOURCE,
                Book.SELLER,
                Finality.AMBIGUOUS,
                "settle_failed",
                null,
                null);
    }

    /** The lower-case payment key. */
    public String key() {
        return authorization.paymentKey();
    }

    /** A deterministic tx hash for this authorization (same for buyer and seller). */
    public String txHash() {
        return "0x" + authorization.nonce().substring(2, 66).replace('0', 'a');
    }

    public static String address(SplittableRandom random) {
        String hex = hex(random, 40);
        // Mixed case, as producers may send checksummed addresses.
        return "0x" + hex.substring(2, 12).toUpperCase(java.util.Locale.ROOT) + hex.substring(12);
    }

    private static String hex(SplittableRandom random, int digits) {
        var sb = new StringBuilder("0x");
        for (int i = 0; i < digits; i++) {
            sb.append(Character.forDigit(random.nextInt(16), 16));
        }
        return sb.toString();
    }

    private static EventMetadata meta(String producer) {
        return new EventMetadata(UUID.randomUUID().toString(), T0, producer, "corr");
    }
}
