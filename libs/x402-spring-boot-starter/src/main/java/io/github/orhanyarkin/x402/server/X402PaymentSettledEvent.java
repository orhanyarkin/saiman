package io.github.orhanyarkin.x402.server;

import io.github.orhanyarkin.x402.core.PaymentRequirements;
import java.time.Instant;
import java.util.UUID;

/**
 * Published (via {@link org.springframework.context.ApplicationEventPublisher}) after {@link
 * X402SettlementFilter} settles a payment successfully: the handler returned a 2xx status and the
 * facilitator's {@code /settle} call reported success with a well-formed transaction hash.
 *
 * <p>Consumed in M4 to feed the ledger's payment inbox. {@code (from, nonce)} is the natural
 * dedupe key -- the same pair the on-chain EIP-3009 nonce and this starter's own {@link
 * PaymentNonceStore} both use. Carries every public, on-chain-derived field of the authorization
 * except the signature itself, which a consumer never needs and this starter never publishes.
 *
 * @param eventId a fresh id for this event, independent of {@code (from, nonce)}
 * @param resourceUrl the request path that was paid for
 * @param requirements the payment requirements that were satisfied
 * @param from the payer's wallet address (the recovered EIP-712 signer)
 * @param nonce the EIP-3009 authorization nonce, {@code 0x} + 64 hex characters
 * @param value the settled amount, as a decimal string of atomic units
 * @param validBefore the authorization's expiry, unix seconds as a decimal string
 * @param payer the payer's wallet address as reported by settlement (normally equal to {@link
 *     #from()})
 * @param transactionHash the on-chain transaction hash, {@code 0x} + 64 hex characters
 * @param settledAt when settlement completed
 */
public record X402PaymentSettledEvent(
        UUID eventId,
        String resourceUrl,
        PaymentRequirements requirements,
        String from,
        String nonce,
        String value,
        String validBefore,
        String payer,
        String transactionHash,
        Instant settledAt) {}
