package io.github.orhanyarkin.x402.testing;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.ExactEvmPayload;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.evm.Eip3009TypedData;
import io.github.orhanyarkin.x402.evm.PaymentSigner;
import java.time.Instant;

/**
 * Builds signed {@code exact}-on-EVM {@link PaymentPayload}s for tests, and the base64 {@code
 * PAYMENT-SIGNATURE} header value to send with them.
 *
 * <p>{@link #authorizationFor(PaymentSigner, PaymentRequirements, Instant)} builds a
 * well-formed, currently-valid authorization matching {@code requirements}; a test that needs a
 * deliberately wrong one (wrong amount, wrong recipient, expired, oversized window, reused nonce)
 * builds its own {@link Eip3009Authorization} from that one's fields (records are immutable, so
 * this means calling the canonical constructor again with the field(s) under test changed) and
 * passes it to {@link #sign(PaymentSigner, PaymentRequirements, Eip3009Authorization)}.
 */
public final class PaymentPayloads {

    /** How far in the past {@code validAfter} is set, so a freshly built payload is immediately usable. */
    private static final long VALID_AFTER_SKEW_SECONDS = 60;

    private PaymentPayloads() {}

    /** A well-formed authorization for {@code requirements}, valid from just before {@code now}. */
    public static Eip3009Authorization authorizationFor(
            PaymentSigner signer, PaymentRequirements requirements, Instant now) {
        long validAfter = Math.max(0, now.getEpochSecond() - VALID_AFTER_SKEW_SECONDS);
        long validBefore = now.getEpochSecond() + requirements.maxTimeoutSeconds();
        return new Eip3009Authorization(
                signer.address(),
                requirements.payTo(),
                requirements.amount(),
                Long.toString(validAfter),
                Long.toString(validBefore),
                Eip3009TypedData.randomNonce());
    }

    /** Signs {@code authorization} with {@code signer} and wraps it as a {@link PaymentPayload} accepting {@code requirements}. */
    public static PaymentPayload sign(
            PaymentSigner signer, PaymentRequirements requirements, Eip3009Authorization authorization) {
        String signature = signer.signTransferWithAuthorization(authorization);
        return new PaymentPayload(2, null, requirements, new ExactEvmPayload(signature, authorization), null);
    }

    /** A well-formed, currently-valid, correctly-signed payload for {@code requirements}. */
    public static PaymentPayload build(PaymentSigner signer, PaymentRequirements requirements) {
        return build(signer, requirements, Instant.now());
    }

    /** As {@link #build(PaymentSigner, PaymentRequirements)}, with an explicit reference time. */
    public static PaymentPayload build(PaymentSigner signer, PaymentRequirements requirements, Instant now) {
        return sign(signer, requirements, authorizationFor(signer, requirements, now));
    }

    /** The base64 {@code PAYMENT-SIGNATURE} header value for {@code payload}. */
    public static String header(X402Codec codec, PaymentPayload payload) {
        return codec.encodePaymentPayload(payload);
    }
}
