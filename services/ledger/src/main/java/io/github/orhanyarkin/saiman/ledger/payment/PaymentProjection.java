package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the ledger knows about one authorization (the {@code ledger.payment} row). Addresses, nonce and tx hashes
 * are lower-case. The id is derived from the payment key, so every replica and every replay agrees on it.
 */
public record PaymentProjection(
        UUID id,
        String paymentKey,
        String network,
        String assetAddress,
        String payer,
        String nonce,
        String payTo,
        Money amount,
        long validBefore,
        @Nullable UUID paymentIntentId,
        @Nullable UUID runId,
        BuyerState buyerState,
        SellerState sellerState,
        ChainState chainState,
        @Nullable String buyerTxHash,
        @Nullable String sellerTxHash,
        @Nullable String chainTxHash,
        @Nullable Instant lastCheckedAt) {

    /** The ledger's id for a payment key: a name-based (v3) UUID, stable across replays. */
    public static UUID paymentId(String paymentKey) {
        return UUID.nameUUIDFromBytes(("saiman-ledger:payment:" + paymentKey).getBytes(StandardCharsets.UTF_8));
    }

    /** The row created by the first fact about an authorization, before that fact is applied. */
    public static PaymentProjection initial(PaymentFact fact) {
        var auth = fact.authorization();
        String key = auth.paymentKey();
        return new PaymentProjection(
                paymentId(key),
                key,
                auth.network(),
                lower(auth.asset()),
                lower(auth.payer()),
                lower(auth.nonce()),
                lower(fact.payTo()),
                fact.amount(),
                auth.validBefore(),
                fact.paymentIntentId(),
                fact.runId(),
                BuyerState.NONE,
                SellerState.NONE,
                ChainState.UNKNOWN,
                null,
                null,
                null,
                null);
    }

    PaymentProjection with(
            BuyerState buyer,
            SellerState seller,
            @Nullable UUID intentId,
            @Nullable UUID run,
            @Nullable String buyerTx,
            @Nullable String sellerTx) {
        return new PaymentProjection(
                id,
                paymentKey,
                network,
                assetAddress,
                payer,
                nonce,
                payTo,
                amount,
                validBefore,
                intentId,
                run,
                buyer,
                seller,
                chainState,
                buyerTx,
                sellerTx,
                chainTxHash,
                lastCheckedAt);
    }

    static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
