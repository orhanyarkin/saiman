package io.github.orhanyarkin.x402.evm;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredData;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;

/**
 * EIP-712 hashing, nonce generation and signature recovery for the fixed EIP-3009 {@code
 * TransferWithAuthorization} struct on {@link TestnetAssets}.
 *
 * <p>Domain: {@code {name: "USDC", version: "2", chainId: 84532, verifyingContract: <test USDC
 * address>}} — the only domain this starter ever signs against (ADR-0008). Verified against the
 * EIP-712 spec's {@code Mail} example vector (see {@code Eip712MailVectorTest}) and against the
 * x402 v2 spec's own {@code exact}-EVM {@code PaymentPayload} example, including its domain
 * separator and {@code TransferWithAuthorization} typehash (see {@code Eip3009TypedDataTest}).
 *
 * <p>Every method here trusts {@link Eip3009Authorization#validate} to have already rejected
 * non-canonical field representations (see that type's Javadoc for why canonical form matters),
 * and re-asserts that trust explicitly at each entry point rather than assuming it silently.
 * Signature parsing enforces the FiatTokenV2 {@code ECRecover} policy USDC itself uses: {@code v}
 * must be 27 or 28, {@code r} must be in {@code [1, n)}, and {@code s} must be in the curve's
 * lower half {@code [1, n/2]} (the low-{@code s} malleability guard) -- two textually different
 * signatures must not be able to recover to the same authorization.
 */
public final class Eip3009TypedData {

    private static final String PRIMARY_TYPE = "TransferWithAuthorization";
    private static final int NONCE_BYTES = 32;
    private static final int SIGNATURE_BYTES = 65;
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("0x[0-9a-fA-F]{130}");
    private static final BigInteger CURVE_ORDER = Sign.CURVE_PARAMS.getN();
    private static final BigInteger CURVE_ORDER_HALF = CURVE_ORDER.shiftRight(1);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Eip3009TypedData() {}

    /**
     * Computes the final EIP-712 digest for {@code authorization}:
     * {@code keccak256("\x19\x01" ‖ domainSeparator ‖ hashStruct(authorization))}.
     *
     * @throws IllegalArgumentException if {@code authorization} somehow fails its own canonical-
     *     form check (see {@link Eip3009Authorization#validate}); unreachable for any instance
     *     that already exists, since its compact constructor cannot be bypassed
     */
    public static byte[] digest(Eip3009Authorization authorization) {
        Eip3009Authorization.validate(authorization);
        return new StructuredDataEncoder(toMessage(authorization)).hashStructuredData();
    }

    /**
     * The EIP-712 domain separator alone (not the full digest): {@code
     * keccak256(encode(EIP712Domain))} for the fixed USDC-on-Base-Sepolia domain. Depends only on
     * that fixed domain, not on {@code authorization}'s field values -- {@code authorization} is
     * only needed because building a {@code StructuredDataEncoder} requires a complete message.
     * Package-private: test support for asserting against the x402 spec's known-answer vector and
     * the live on-chain {@code DOMAIN_SEPARATOR()} (see {@code Eip3009TypedDataTest} and {@code
     * Eip3009DomainSeparatorLiveTest}).
     */
    static byte[] domainSeparator(Eip3009Authorization authorization) {
        Eip3009Authorization.validate(authorization);
        return new StructuredDataEncoder(toMessage(authorization)).hashDomain();
    }

    /**
     * The {@code TransferWithAuthorization} EIP-712 typehash: {@code
     * keccak256("TransferWithAuthorization(address from,address to,uint256 value,uint256"
     * + " validAfter,uint256 validBefore,bytes32 nonce)")}. Structural, so -- like {@link
     * #domainSeparator} -- independent of {@code authorization}'s field values. Package-private
     * test support, as above.
     */
    static byte[] transferWithAuthorizationTypeHash(Eip3009Authorization authorization) {
        Eip3009Authorization.validate(authorization);
        return new StructuredDataEncoder(toMessage(authorization)).typeHash(PRIMARY_TYPE);
    }

    /** Generates a fresh, random 32-byte nonce as a {@code 0x}-prefixed hex string. */
    public static String randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(nonce);
        return Numeric.toHexString(nonce);
    }

    /**
     * Packs a web3j signature into the wire format: {@code 0x} + 65 bytes of {@code r ‖ s ‖ v}
     * ({@code v} = 27 or 28).
     *
     * <p>Package-private: {@code Sign.SignatureData} is a web3j type and does not appear in this
     * starter's own public API (see {@code build.gradle.kts}'s comment on why web3j-crypto is
     * {@code implementation}, not {@code api}).
     *
     * @throws IllegalArgumentException if {@code signature}'s {@code r}/{@code s} are not each 32
     *     bytes, or its {@code v} is not 27 or 28
     */
    static String toSignatureHex(Sign.SignatureData signature) {
        byte[] r = signature.getR();
        byte[] s = signature.getS();
        if (r.length != 32 || s.length != 32) {
            throw new IllegalArgumentException("signature r and s must each be 32 bytes");
        }
        byte v = signature.getV()[signature.getV().length - 1];
        if (v != 27 && v != 28) {
            throw new IllegalArgumentException("signature recovery byte (v) must be 27 or 28");
        }
        byte[] combined = new byte[SIGNATURE_BYTES];
        System.arraycopy(r, 0, combined, 0, 32);
        System.arraycopy(s, 0, combined, 32, 32);
        combined[64] = v;
        return Numeric.toHexString(combined);
    }

    /**
     * Unpacks and validates a wire-format {@code r ‖ s ‖ v} signature hex string.
     *
     * <p>Package-private for the same reason as {@link #toSignatureHex}. Enforces the low-{@code
     * s} malleability guard (see this class's Javadoc) in addition to basic shape checks, so a
     * signature that would pass a naive ECRecover but fail USDC's FiatTokenV2 contract is
     * rejected here too, before it ever reaches a facilitator.
     *
     * @throws IllegalArgumentException if the signature is not {@code 0x} + 130 hex characters,
     *     its {@code v} is not 27 or 28, {@code r} is not in {@code [1, n)}, or {@code s} is not
     *     in {@code [1, n/2]}; the message never echoes {@code signatureHex} itself
     */
    static Sign.SignatureData parseSignatureHex(String signatureHex) {
        if (signatureHex == null || !SIGNATURE_PATTERN.matcher(signatureHex).matches()) {
            throw new IllegalArgumentException("signature must be a 0x-prefixed 65-byte (130 hex character) value");
        }
        byte[] bytes = Numeric.hexStringToByteArray(signatureHex);
        byte[] r = Arrays.copyOfRange(bytes, 0, 32);
        byte[] s = Arrays.copyOfRange(bytes, 32, 64);
        byte v = bytes[64];
        if (v != 27 && v != 28) {
            throw new IllegalArgumentException("signature recovery byte (v) must be 27 or 28");
        }
        BigInteger rValue = Numeric.toBigInt(r);
        BigInteger sValue = Numeric.toBigInt(s);
        if (rValue.signum() <= 0 || rValue.compareTo(CURVE_ORDER) >= 0) {
            throw new IllegalArgumentException("signature r must be in range [1, n)");
        }
        if (sValue.signum() <= 0 || sValue.compareTo(CURVE_ORDER_HALF) > 0) {
            throw new IllegalArgumentException(
                    "signature s must be in the curve's lower half [1, n/2] (malleability guard, matches"
                            + " FiatTokenV2's ECRecover policy)");
        }
        return new Sign.SignatureData(v, r, s);
    }

    /**
     * Recovers the signer address from an authorization and its signature.
     *
     * <p>Tampering with any field of {@code authorization}, or with {@code signatureHex} itself,
     * changes the digest or the recovered public key and therefore the returned address.
     *
     * @param authorization the authorization that was (purportedly) signed
     * @param signatureHex the {@code 0x}-prefixed 65-byte {@code r ‖ s ‖ v} signature
     * @return the EIP-55 checksummed address that produced the signature
     * @throws IllegalArgumentException if the signature is malformed (see {@link
     *     #parseSignatureHex}) or does not recover to a valid public key
     */
    public static String recoverSigner(Eip3009Authorization authorization, String signatureHex) {
        Sign.SignatureData signature = parseSignatureHex(signatureHex);
        int recId = signature.getV()[signature.getV().length - 1] - 27;
        byte[] digest = digest(authorization);
        ECDSASignature ecdsaSignature =
                new ECDSASignature(Numeric.toBigInt(signature.getR()), Numeric.toBigInt(signature.getS()));
        BigInteger publicKey = Sign.recoverFromSignature(recId, ecdsaSignature, digest);
        if (publicKey == null) {
            throw new IllegalArgumentException("could not recover a signer from the EIP-3009 signature");
        }
        return Keys.toChecksumAddress(Keys.getAddress(publicKey));
    }

    /**
     * Verifies that {@code signatureHex} was produced by {@code authorization.from()}.
     *
     * <p>Unlike {@link #recoverSigner}, this never throws: a malformed signature simply fails
     * verification. Intended for a facilitator-side check (e.g. a {@code FakeFacilitator} test
     * fixture) where "not from the claimed payer" and "not a well-formed signature at all" should
     * be treated the same way.
     */
    public static boolean verify(Eip3009Authorization authorization, String signatureHex) {
        try {
            return recoverSigner(authorization, signatureHex).equalsIgnoreCase(authorization.from());
        } catch (IllegalArgumentException malformedSignature) {
            return false;
        }
    }

    private static StructuredData.EIP712Message toMessage(Eip3009Authorization authorization) {
        HashMap<String, List<StructuredData.Entry>> types = new HashMap<>();
        types.put(
                "EIP712Domain",
                List.of(
                        new StructuredData.Entry("name", "string"),
                        new StructuredData.Entry("version", "string"),
                        new StructuredData.Entry("chainId", "uint256"),
                        new StructuredData.Entry("verifyingContract", "address")));
        types.put(
                PRIMARY_TYPE,
                List.of(
                        new StructuredData.Entry("from", "address"),
                        new StructuredData.Entry("to", "address"),
                        new StructuredData.Entry("value", "uint256"),
                        new StructuredData.Entry("validAfter", "uint256"),
                        new StructuredData.Entry("validBefore", "uint256"),
                        new StructuredData.Entry("nonce", "bytes32")));

        StructuredData.EIP712Domain domain = new StructuredData.EIP712Domain(
                TestnetAssets.USDC_NAME,
                TestnetAssets.USDC_VERSION,
                Long.toString(TestnetAssets.CHAIN_ID),
                TestnetAssets.USDC_ADDRESS,
                null);

        // Parsed BigInteger for uint256 fields and lower-cased hex for address/bytes32 fields:
        // web3j's own ABI encoding is lenient about non-canonical textual forms (mixed case,
        // "0x2710" vs "10000", leading zeros), so this starter never hands it raw wire strings
        // to interpret -- even though Eip3009Authorization.validate above already guarantees
        // those strings are canonical, this is a second, independent line of defense.
        HashMap<String, Object> message = new HashMap<>();
        message.put("from", authorization.from().toLowerCase(Locale.ROOT));
        message.put("to", authorization.to().toLowerCase(Locale.ROOT));
        message.put("value", new BigInteger(authorization.value()));
        message.put("validAfter", new BigInteger(authorization.validAfter()));
        message.put("validBefore", new BigInteger(authorization.validBefore()));
        message.put("nonce", authorization.nonce().toLowerCase(Locale.ROOT));

        return new StructuredData.EIP712Message(types, PRIMARY_TYPE, message, domain);
    }
}
