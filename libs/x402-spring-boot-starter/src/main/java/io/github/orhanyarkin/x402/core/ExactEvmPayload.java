package io.github.orhanyarkin.x402.core;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The {@code exact}-scheme, EVM-network payment payload: an EIP-712 signature over an EIP-3009
 * authorization.
 *
 * <p>Mirrors the {@code payload} object described for the {@code eip3009} asset transfer method
 * in specs/schemes/exact/scheme_exact_evm.md.
 *
 * @param signature {@code 0x}-prefixed 65-byte (130 hex character) {@code r ‖ s ‖ v} EIP-712
 *     signature over {@link #authorization()}
 * @param authorization the signed EIP-3009 {@code transferWithAuthorization} parameters
 */
public record ExactEvmPayload(String signature, Eip3009Authorization authorization) {

    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("0x[0-9a-fA-F]{130}");

    public ExactEvmPayload {
        Objects.requireNonNull(signature, "signature must not be null");
        Objects.requireNonNull(authorization, "authorization must not be null");
        if (!SIGNATURE_PATTERN.matcher(signature).matches()) {
            // Never echo the signature itself in the message (ADR-0006 amendment).
            throw new IllegalArgumentException("signature must be a 0x-prefixed 65-byte hex value");
        }
    }
}
