package io.github.orhanyarkin.saiman.shared.payments;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The on-chain identity of one EIP-3009 authorization. Its {@link #paymentKey()} is the business key every
 * consumer dedupes on, independently of event ids.
 *
 * @param network CAIP-2 network, {@code eip155:84532} (Base Sepolia) only
 * @param asset token contract address
 * @param payer the authorizer ({@code from})
 * @param nonce the 32-byte EIP-3009 nonce, hex
 * @param validBefore unix seconds after which the authorization can no longer be used
 */
public record AuthorizationRef(String network, String asset, String payer, String nonce, long validBefore) {

    public static final String BASE_SEPOLIA = "eip155:84532";

    /** Test USDC on Base Sepolia, the only asset this system pays or books. */
    public static final String USDC = "0x036CbD53842c5426634e7929541eC2318f3dCF7e";

    /** 2100-01-01T00:00:00Z: an upper bound so a forged validBefore cannot overflow date arithmetic downstream. */
    public static final long MAX_VALID_BEFORE = 4_102_444_800L;

    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final Pattern NONCE = Pattern.compile("0x[0-9a-fA-F]{64}");

    public AuthorizationRef {
        if (!BASE_SEPOLIA.equals(network)) {
            throw new IllegalArgumentException("only the Base Sepolia testnet is supported");
        }
        if (!ADDRESS.matcher(asset).matches() || !ADDRESS.matcher(payer).matches()) {
            throw new IllegalArgumentException("asset and payer must be 0x addresses");
        }
        if (!NONCE.matcher(nonce).matches()) {
            throw new IllegalArgumentException("nonce must be 32 bytes of hex");
        }
        if (!USDC.equalsIgnoreCase(asset)) {
            throw new IllegalArgumentException("only Base Sepolia test USDC is supported");
        }
        if (validBefore <= 0 || validBefore >= MAX_VALID_BEFORE) {
            throw new IllegalArgumentException("validBefore must be positive and before 2100");
        }
    }

    /** {@code network:asset:payer:nonce}, lower-case. */
    public String paymentKey() {
        return (network + ":" + asset + ":" + payer + ":" + nonce).toLowerCase(Locale.ROOT);
    }
}
