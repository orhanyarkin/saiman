package io.github.orhanyarkin.x402.evm;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;

/**
 * Signs EIP-3009 {@code transferWithAuthorization} authorizations on behalf of a buyer wallet.
 *
 * <p>Deliberately narrow: implementations expose only an address and a signing operation, never
 * the private key. {@link #signTransferWithAuthorization(Eip3009Authorization)} does not generate
 * the nonce or choose the amount/recipient/time window — the caller builds the complete {@link
 * Eip3009Authorization} first (see {@link Eip3009TypedData#randomNonce()}), so a signer
 * implementation can be a hardware wallet or a remote signing service just as well as a raw
 * private key.
 */
public interface PaymentSigner {

    /** The signer's wallet address (EIP-55 checksummed). */
    String address();

    /**
     * Signs {@code authorization} against the fixed USDC-on-Base-Sepolia EIP-712 domain (see
     * {@link io.github.orhanyarkin.x402.core.TestnetAssets}).
     *
     * @return the {@code 0x}-prefixed 65-byte {@code r ‖ s ‖ v} signature
     */
    String signTransferWithAuthorization(Eip3009Authorization authorization);
}
