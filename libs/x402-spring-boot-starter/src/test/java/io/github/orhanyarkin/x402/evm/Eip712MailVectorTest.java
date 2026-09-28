package io.github.orhanyarkin.x402.evm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;

/**
 * Verifies this starter's EIP-712 dependency (web3j's {@link StructuredDataEncoder}, the same
 * machinery {@link Eip3009TypedData} uses) against the {@code Mail} example vector from the
 * EIP-712 specification itself (see {@code src/test/resources/spec/NOTICE}).
 *
 * <p>The private key for "Cow" in that example is the well-known test key {@code
 * keccak256("cow")}; every expected hash and the final signature below are copied verbatim from
 * <a href="https://eips.ethereum.org/EIPS/eip-712">EIP-712</a>.
 */
class Eip712MailVectorTest {

    private static final String EXPECTED_TYPE_HASH =
            "0xa0cedeb2dc280ba39b857546d74f5549c3a1d7bdc2dd96bf881f76108e23dac2";
    private static final String EXPECTED_DOMAIN_STRUCT_HASH =
            "0xf2cee375fa42b42143804025fc449deafd50cc031ca257e0b194a650a912090f";
    private static final String EXPECTED_MESSAGE_STRUCT_HASH =
            "0xc52c0ee5d84264471806290a3f2c4cecfc5490626bf912d01f240d7a274b371e";
    private static final String EXPECTED_DIGEST = "0xbe609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2";
    private static final String COW_ADDRESS = "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826";
    private static final String EXPECTED_SIGNATURE =
            "0x4355c47d63924e8a72e509b65029052eb6c299d53a04e167c5775fd466751c9d07299936d304c153f6443dfa05f40ff007d72911b6f72307f996231605b915621c";

    @SuppressWarnings("unchecked")
    @Test
    void reproducesEveryHashAndTheKnownCowSignature() throws Exception {
        String json = readSpecFixture("eip712-mail-example.json");
        StructuredDataEncoder encoder = new StructuredDataEncoder(json);

        byte[] typeHash = encoder.typeHash(encoder.jsonMessageObject.getPrimaryType());
        assertThat(Numeric.toHexString(typeHash)).isEqualTo(EXPECTED_TYPE_HASH);

        byte[] domainHash = encoder.hashDomain();
        assertThat(Numeric.toHexString(domainHash)).isEqualTo(EXPECTED_DOMAIN_STRUCT_HASH);

        byte[] messageHash =
                encoder.hashMessage(encoder.jsonMessageObject.getPrimaryType(), (java.util.HashMap<String, Object>)
                        encoder.jsonMessageObject.getMessage());
        assertThat(Numeric.toHexString(messageHash)).isEqualTo(EXPECTED_MESSAGE_STRUCT_HASH);

        byte[] digest = encoder.hashStructuredData();
        assertThat(Numeric.toHexString(digest)).isEqualTo(EXPECTED_DIGEST);

        // The "cow" private key from the EIP-712 reference examples: keccak256("cow").
        BigInteger cowPrivateKey = Numeric.toBigInt(Hash.sha3("cow".getBytes(StandardCharsets.UTF_8)));
        ECKeyPair cowKeyPair = ECKeyPair.create(cowPrivateKey);
        assertThat(Keys.toChecksumAddress(Keys.getAddress(cowKeyPair))).isEqualToIgnoringCase(COW_ADDRESS);

        Sign.SignatureData signature = Sign.signMessage(digest, cowKeyPair, false);
        String signatureHex = Numeric.toHexString(signature.getR())
                + Numeric.toHexStringNoPrefix(signature.getS())
                + Numeric.toHexStringNoPrefix(new BigInteger(1, signature.getV()));
        assertThat(signatureHex).isEqualTo(EXPECTED_SIGNATURE);

        // And the signature recovers back to Cow's address.
        int recId = signature.getV()[signature.getV().length - 1] - 27;
        ECDSASignature ecdsaSignature =
                new ECDSASignature(Numeric.toBigInt(signature.getR()), Numeric.toBigInt(signature.getS()));
        BigInteger recoveredPublicKey = Sign.recoverFromSignature(recId, ecdsaSignature, digest);
        assertThat(Keys.toChecksumAddress(Keys.getAddress(recoveredPublicKey))).isEqualToIgnoringCase(COW_ADDRESS);
    }

    private static String readSpecFixture(String name) throws IOException {
        try (InputStream in = Eip712MailVectorTest.class.getResourceAsStream("/spec/" + name)) {
            if (in == null) {
                throw new IOException("spec fixture not found on classpath: spec/" + name);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
