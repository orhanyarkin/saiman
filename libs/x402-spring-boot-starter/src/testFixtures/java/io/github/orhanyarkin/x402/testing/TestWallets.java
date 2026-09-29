package io.github.orhanyarkin.x402.testing;

import io.github.orhanyarkin.x402.evm.PrivateKeyPaymentSigner;
import java.nio.charset.StandardCharsets;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

/**
 * Fixed test wallets for x402 starter tests, deterministically derived from well-known passphrases
 * rather than hand-copied hex literals: a private key is exactly 32 bytes, and transcribing one by
 * hand risks silently dropping or duplicating a character (see the "Cow" key discussion in {@code
 * Eip712MailVectorTest}). Both wallets are re-derived here the same way, from {@code keccak256} of
 * an ASCII passphrase, so there is nothing to transcribe.
 *
 * <p>These are test-only keys with no funds anywhere; never use them outside a test.
 */
public final class TestWallets {

    /** The EIP-712 specification's own "Cow" test signer ({@code keccak256("cow")}). */
    public static final PrivateKeyPaymentSigner PAYER = fromPassphrase("cow");

    /** A second fixed signer, for multi-payer or "wrong signer" test scenarios. */
    public static final PrivateKeyPaymentSigner OTHER_PAYER = fromPassphrase("horse");

    private TestWallets() {}

    private static PrivateKeyPaymentSigner fromPassphrase(String passphrase) {
        byte[] hash = Hash.sha3(passphrase.getBytes(StandardCharsets.UTF_8));
        return new PrivateKeyPaymentSigner(Numeric.toHexStringNoPrefix(hash));
    }
}
