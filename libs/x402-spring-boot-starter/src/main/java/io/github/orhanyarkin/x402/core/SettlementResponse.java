package io.github.orhanyarkin.x402.core;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The facilitator's answer to {@code POST /settle}, and the payload the server echoes back on the
 * {@code PAYMENT-RESPONSE} header after settlement.
 *
 * <p>Mirrors the x402 v2 {@code SettleResponse} schema (specs/x402-specification-v2.md, section
 * 5.3), plus {@link #errorMessage()} and {@link #extensionResponses()} carried by the reference
 * TypeScript implementation ({@code typescript/packages/core/src/types/facilitator.ts} at commit
 * {@code c84154b5d6a31d77fd5b9dbb01213053fd9cb9eb}). {@link #transaction()} is the empty string
 * when no transaction was broadcast, and MUST be non-empty when {@link #errorReason()} is {@code
 * "settlement_pending"} (section 9).
 *
 * @param success whether settlement succeeded
 * @param errorReason machine-readable reason for failure, e.g. {@code "insufficient_funds"}
 *     (omitted on success)
 * @param errorMessage human-readable detail for {@link #errorReason()} (omitted on success)
 * @param payer address of the payer's wallet
 * @param transaction blockchain transaction hash, or {@code ""} if none was broadcast
 * @param network blockchain network identifier in CAIP-2 format
 * @param amount the actual amount settled, as a decimal string of atomic units (omitted if not
 *     applicable)
 * @param extensions protocol extensions data
 * @param extensionResponses per-extension outcomes from the facilitator's HTTP sidechannel
 *     (section 7.2.1); never forwarded to buyers
 * @param extra scheme-specific additional data
 */
public record SettlementResponse(
        boolean success,
        @Nullable String errorReason,
        @Nullable String errorMessage,
        @Nullable String payer,
        String transaction,
        String network,
        @Nullable String amount,
        @Nullable Map<String, Object> extensions,
        @Nullable Map<String, Object> extensionResponses,
        @Nullable Map<String, Object> extra) {}
