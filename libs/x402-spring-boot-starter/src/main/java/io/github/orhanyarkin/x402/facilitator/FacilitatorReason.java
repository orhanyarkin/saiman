package io.github.orhanyarkin.x402.facilitator;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A closed set of facilitator failure reasons, safe to use as a metric/trace tag value.
 *
 * <p>The facilitator's {@code errorReason}/{@code invalidReason} is a free string. Used directly as
 * a tag it would let a hostile or buggy facilitator create unbounded meter cardinality, so every
 * reason is first mapped through {@link #fromCode(String)}: a known x402 exact-EVM code maps to its
 * constant, {@code null}/empty to {@link #NONE}, and anything else to {@link #OTHER}. The wire
 * code itself is still logged by the caller (when it has the bounded {@code [a-z0-9_]{1,64}}
 * shape), so an {@link #OTHER} reason stays investigable from the log.
 *
 * <p>Codes come from the x402 v2 exact-EVM facilitator ({@code
 * go/mechanisms/evm/exact/facilitator/errors.go} in the x402 repository) plus the generic codes
 * the spec and the public testnet facilitator use ({@code settlement_pending}, {@code
 * insufficient_funds}, {@code invalid_transaction_state}).
 */
public enum FacilitatorReason {
    /** No reason was given (success, or a failure that carried no code). */
    NONE,
    /** The facilitator broadcast a transaction but has not seen it confirmed; the hash is set. */
    SETTLEMENT_PENDING,
    /** The payer's balance is insufficient. */
    INSUFFICIENT_FUNDS,
    /** The settlement transaction is in a state the facilitator rejects. */
    INVALID_TRANSACTION_STATE,
    /** The broadcast transaction's receipt reported failure, or waiting for it failed. */
    INVALID_EXACT_EVM_TRANSACTION_FAILED,
    /** The facilitator could not fetch the transaction receipt. */
    INVALID_EXACT_EVM_FAILED_TO_GET_RECEIPT,
    /** The facilitator could not execute (broadcast) the transfer. */
    INVALID_EXACT_EVM_FAILED_TO_EXECUTE_TRANSFER,
    /** The facilitator's pre-settle verification failed. */
    INVALID_EXACT_EVM_VERIFICATION_FAILED,
    /** The facilitator could not parse the signature. */
    INVALID_EXACT_EVM_FAILED_TO_PARSE_SIGNATURE,
    /** The facilitator could not check whether the payer is a deployed smart wallet. */
    INVALID_EXACT_EVM_FAILED_TO_CHECK_DEPLOYMENT,
    /** The transfer simulation failed. */
    INVALID_EXACT_EVM_TRANSACTION_SIMULATION_FAILED,
    /** The authorization nonce was already used on chain. */
    INVALID_EXACT_EVM_NONCE_ALREADY_USED,
    /** The payer's token balance is below the amount. */
    INVALID_EXACT_EVM_INSUFFICIENT_BALANCE,
    /** The authorization is expired ({@code validBefore} passed). */
    INVALID_EXACT_EVM_PAYLOAD_AUTHORIZATION_VALID_BEFORE,
    /** The authorization is not yet valid ({@code validAfter} not reached). */
    INVALID_EXACT_EVM_PAYLOAD_AUTHORIZATION_VALID_AFTER,
    /** The authorization value does not match the required amount. */
    INVALID_EXACT_EVM_PAYLOAD_AUTHORIZATION_VALUE_MISMATCH,
    /** The recipient does not match the requirements. */
    INVALID_EXACT_EVM_RECIPIENT_MISMATCH,
    /** The signature is invalid. */
    INVALID_EXACT_EVM_SIGNATURE,
    /** The network does not match the requirements. */
    INVALID_EXACT_EVM_NETWORK_MISMATCH,
    /** Any other code (known or not): the log, not the tag, keeps it. */
    OTHER;

    private static final Map<String, FacilitatorReason> BY_CODE = new HashMap<>();

    static {
        for (FacilitatorReason reason : values()) {
            if (reason != NONE && reason != OTHER) {
                BY_CODE.put(reason.code(), reason);
            }
        }
    }

    /** The tag value and wire code: the lower-case constant name. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Maps a facilitator-supplied reason string to this closed set; never throws.
     *
     * @return {@link #NONE} for {@code null}/empty, the matching constant for a known code, else
     *     {@link #OTHER}
     */
    public static FacilitatorReason fromCode(@Nullable String code) {
        if (code == null || code.isEmpty()) {
            return NONE;
        }
        FacilitatorReason known = BY_CODE.get(code);
        return known != null ? known : OTHER;
    }
}
