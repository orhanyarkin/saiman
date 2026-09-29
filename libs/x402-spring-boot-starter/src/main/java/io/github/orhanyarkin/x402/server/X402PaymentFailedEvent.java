package io.github.orhanyarkin.x402.server;

import io.github.orhanyarkin.x402.core.PaymentRequirements;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Published (via {@link org.springframework.context.ApplicationEventPublisher}) when a verified
 * payment authorization could not be settled: the facilitator's {@code /settle} call reported
 * failure, timed out, threw, or returned success without a well-formed transaction hash.
 *
 * <p>The payment authorization's nonce claim is deliberately kept (see {@link PaymentNonceStore}):
 * the outcome is ambiguous, since a third party may have front-run settlement on chain. M4's
 * reconciliation job checks {@code authorizationState} on chain rather than trusting this event
 * alone. {@code (from, nonce)} is the natural dedupe key, matching {@link
 * X402PaymentSettledEvent}.
 *
 * @param eventId a fresh id for this event, independent of {@code (from, nonce)}
 * @param resourceUrl the request path that was being paid for
 * @param requirements the payment requirements that were being satisfied
 * @param from the payer's wallet address (the recovered EIP-712 signer)
 * @param nonce the EIP-3009 authorization nonce, {@code 0x} + 64 hex characters
 * @param value the amount that was to be settled, as a decimal string of atomic units
 * @param validBefore the authorization's expiry, unix seconds as a decimal string
 * @param payer the payer's wallet address as last known (may equal {@link #from()})
 * @param errorReason the facilitator's machine-readable failure reason, if any
 * @param failedAt when the failure was observed
 */
public record X402PaymentFailedEvent(
        UUID eventId,
        String resourceUrl,
        PaymentRequirements requirements,
        String from,
        String nonce,
        String value,
        String validBefore,
        @Nullable String payer,
        @Nullable String errorReason,
        Instant failedAt) {}
