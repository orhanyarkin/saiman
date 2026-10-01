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
        if (validBefore <= 0) {
            throw new IllegalArgumentException("validBefore must be positive");
        }
    }

    /** {@code network:asset:payer:nonce}, lower-case. */
    public String paymentKey() {
        return (network + ":" + asset + ":" + payer + ":" + nonce).toLowerCase(Locale.ROOT);
    }
}
