package io.github.orhanyarkin.x402.core;

import java.util.Objects;

/**
 * The single network and asset this starter supports: Base Sepolia testnet USDC.
 *
 * <p>ADR-0008 fixes these in code rather than exposing a network or asset configuration property:
 * the server only ever offers this pair, the client rejects any other, and the EVM signer only
 * ever builds the EIP-712 domain below. This keeps the starter from being pointed at mainnet by
 * accident (rule 1 in {@code CLAUDE.md}: testnet only).
 */
public final class TestnetAssets {

    /** CAIP-2 identifier for Base Sepolia. */
    public static final String NETWORK = "eip155:84532";

    /** EIP-155 chain id for Base Sepolia. */
    public static final long CHAIN_ID = 84532L;

    /** Test USDC contract address on Base Sepolia. */
    public static final String USDC_ADDRESS = "0x036CbD53842c5426634e7929541eC2318f3dCF7e";

    /** EIP-712 domain name for the test USDC contract. */
    public static final String USDC_NAME = "USDC";

    /** EIP-712 domain version for the test USDC contract. */
    public static final String USDC_VERSION = "2";

    /** Decimal places for USDC atomic units. */
    public static final int USDC_DECIMALS = 6;

    /** The only payment scheme this starter implements. */
    public static final String SCHEME_EXACT = "exact";

    /** Default (and only supported) {@code extra.assetTransferMethod} for {@code exact} on EVM. */
    private static final String ASSET_TRANSFER_METHOD_EIP3009 = "eip3009";

    /** Default (and only supported) {@code extra.paymentFlow} (section 6.1 of the spec). */
    private static final String PAYMENT_FLOW_AUTHORIZATION = "authorization";

    private TestnetAssets() {}

    /**
     * Checks that {@code requirements} is exactly the one payment method this starter supports:
     * {@code exact} scheme, {@link #NETWORK}, test USDC, EIP-712 domain {@code name}/{@code
     * version} matching {@link #USDC_NAME}/{@link #USDC_VERSION}, and -- if present at all --
     * {@code extra.assetTransferMethod} of {@code "eip3009"} and {@code extra.paymentFlow} of
     * {@code "authorization"} (the protocol-reserved keys from spec section 6.1; both are
     * optional and default to those values, so their absence is accepted). This is the one shared
     * check the server and client sides of this starter both call: a payment offer/acceptance
     * that doesn't pass this is not one this starter can ever settle.
     *
     * <p>No failure message echoes any value from {@code requirements}: {@code network}/{@code
     * asset} are address-like identifiers, and the {@code extra} fields are
     * attacker-influenceable scheme tokens a caller could use to poison logs or error responses
     * (a Spring bind-failure report, or any other logger downstream, would otherwise print them
     * verbatim). Callers who need the rejected value for their own diagnostics already have it,
     * since they passed {@code requirements} in.
     *
     * @throws UnsupportedPaymentException if any of the above does not hold
     */
    public static void requireSupported(PaymentRequirements requirements) {
        Objects.requireNonNull(requirements, "requirements must not be null");
        if (!SCHEME_EXACT.equals(requirements.scheme())) {
            throw new UnsupportedPaymentException("unsupported scheme: this starter only supports " + SCHEME_EXACT);
        }
        if (!NETWORK.equals(requirements.network())) {
            throw new UnsupportedPaymentException("unsupported network: this starter only supports " + NETWORK);
        }
        if (!USDC_ADDRESS.equalsIgnoreCase(requirements.asset())) {
            throw new UnsupportedPaymentException("unsupported asset: this starter only supports " + USDC_ADDRESS);
        }
        if (!USDC_NAME.equals(requirements.extraString("name"))) {
            throw new UnsupportedPaymentException("unsupported extra.name: this starter only supports " + USDC_NAME);
        }
        if (!USDC_VERSION.equals(requirements.extraString("version"))) {
            throw new UnsupportedPaymentException(
                    "unsupported extra.version: this starter only supports " + USDC_VERSION);
        }
        String assetTransferMethod = requirements.extraString("assetTransferMethod");
        if (assetTransferMethod != null && !ASSET_TRANSFER_METHOD_EIP3009.equals(assetTransferMethod)) {
            throw new UnsupportedPaymentException("unsupported extra.assetTransferMethod: this starter only supports "
                    + ASSET_TRANSFER_METHOD_EIP3009);
        }
        String paymentFlow = requirements.extraString("paymentFlow");
        if (paymentFlow != null && !PAYMENT_FLOW_AUTHORIZATION.equals(paymentFlow)) {
            throw new UnsupportedPaymentException(
                    "unsupported extra.paymentFlow: this starter only supports " + PAYMENT_FLOW_AUTHORIZATION);
        }
    }
}
