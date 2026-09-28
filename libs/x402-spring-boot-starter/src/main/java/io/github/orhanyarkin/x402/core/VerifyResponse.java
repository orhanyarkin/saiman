package io.github.orhanyarkin.x402.core;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The facilitator's answer to {@code POST /verify}.
 *
 * <p>Mirrors the x402 v2 {@code VerifyResponse} schema (specs/x402-specification-v2.md, section
 * 5.4), plus {@link #invalidMessage()} and {@link #extensionResponses()} carried by the reference
 * TypeScript implementation ({@code typescript/packages/core/src/types/facilitator.ts} at commit
 * {@code c84154b5d6a31d77fd5b9dbb01213053fd9cb9eb}). Note the wire field is literally {@code
 * isValid}; the record component name matches it exactly so {@link X402Codec} needs no property
 * renaming.
 *
 * @param isValid whether the payment authorization is valid
 * @param invalidReason machine-readable reason for invalidity, e.g. {@code "insufficient_funds"}
 *     (omitted when valid)
 * @param invalidMessage human-readable detail for {@link #invalidReason()} (omitted when valid)
 * @param payer address of the payer's wallet, recovered from the signature
 * @param extensions protocol extensions data
 * @param extensionResponses per-extension outcomes from the facilitator's HTTP sidechannel
 *     (section 7.2.1); never forwarded to buyers
 * @param extra scheme-specific additional data
 */
public record VerifyResponse(
        boolean isValid,
        @Nullable String invalidReason,
        @Nullable String invalidMessage,
        @Nullable String payer,
        @Nullable Map<String, Object> extensions,
        @Nullable Map<String, Object> extensionResponses,
        @Nullable Map<String, Object> extra) {}
