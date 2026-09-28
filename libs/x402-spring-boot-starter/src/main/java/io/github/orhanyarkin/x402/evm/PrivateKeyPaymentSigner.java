package io.github.orhanyarkin.x402.evm;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import java.math.BigInteger;
import java.util.regex.Pattern;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/**
 * A {@link PaymentSigner} backed by a raw secp256k1 private key, held only in memory.
 *
 * <p>Testnet-only by construction: it signs exclusively against the fixed USDC-on-Base-Sepolia
 * domain in {@link io.github.orhanyarkin.x402.core.TestnetAssets}. Never logs, serializes or
 * exposes the private key; {@link #toString()} reveals only the address (rule 1/ADR-0009 in
 * {@code CLAUDE.md}: wallet keys are never logged). Deliberately has no Bean Validation
 * annotations anywhere near the key: Spring Boot's configuration-property bind-failure report
 * prints the rejected value, which would put the key in a startup log. Validation happens here,
 * in code, and every failure message below omits the input value entirely.
 */
public final class PrivateKeyPaymentSigner implements PaymentSigner {

    // Exactly 64 hex digits (32 bytes): a secp256k1 private key is a fixed-width scalar, not an
    // arbitrary-length number, so this also rejects 63- or 65-digit inputs that would otherwise
    // parse as *some* number.
    private static final Pattern HEX_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");
    private static final BigInteger CURVE_ORDER = Sign.CURVE_PARAMS.getN();

    private final ECKeyPair keyPair;
    private final String address;

    /**
     * @param privateKeyHex the private key as 64 hex digits, with or without a {@code 0x} prefix
     * @throws IllegalArgumentException if the key is missing, not exactly 64 hex digits, or not
     *     in the valid secp256k1 scalar range {@code [1, n)}; the message never includes {@code
     *     privateKeyHex} or any value derived from it
     */
    public PrivateKeyPaymentSigner(String privateKeyHex) {
        BigInteger privateKey = parsePrivateKey(privateKeyHex);
        this.keyPair = ECKeyPair.create(privateKey);
        this.address = Keys.toChecksumAddress(Keys.getAddress(keyPair));
    }

    private static BigInteger parsePrivateKey(String privateKeyHex) {
        if (privateKeyHex == null || privateKeyHex.isEmpty()) {
            throw new IllegalArgumentException("private key must not be null or empty");
        }
        String hex = Numeric.cleanHexPrefix(privateKeyHex);
        if (!HEX_PATTERN.matcher(hex).matches()) {
            // Never echo the input: it may be a secret key or a fragment of one.
            throw new IllegalArgumentException("private key must be exactly 64 hex digits");
        }
        BigInteger value;
        try {
            value = new BigInteger(hex, 16);
        } catch (NumberFormatException e) {
            // Deliberately no cause: NumberFormatException's own message can include the input.
            throw new IllegalArgumentException("private key is not a valid hex number");
        }
        if (value.signum() <= 0 || value.compareTo(CURVE_ORDER) >= 0) {
            throw new IllegalArgumentException("private key must be in range [1, n) for secp256k1");
        }
        return value;
    }

    @Override
    public String address() {
        return address;
    }

    @Override
    public String signTransferWithAuthorization(Eip3009Authorization authorization) {
        byte[] digest = Eip3009TypedData.digest(authorization);
        Sign.SignatureData signature = Sign.signMessage(digest, keyPair, false);
        return Eip3009TypedData.toSignatureHex(signature);
    }

    /** Returns only the signer's address; never the private key. */
    @Override
    public String toString() {
        return "PrivateKeyPaymentSigner[address=" + address + "]";
    }
}
