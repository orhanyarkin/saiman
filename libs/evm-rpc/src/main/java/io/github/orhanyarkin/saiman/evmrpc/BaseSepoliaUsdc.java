package io.github.orhanyarkin.saiman.evmrpc;

import java.util.Optional;

/**
 * Read-only facts about the Base Sepolia test-USDC contract. Implementations verify {@code eth_chainId == 84532}
 * at startup, accept only an https URL on an exact-host allowlist (or loopback for tests), never follow
 * redirects, compute selectors and topics with Keccak-256 instead of hard-coding them, and throw
 * {@link ChainUnavailableException} instead of guessing.
 */
public interface BaseSepoliaUsdc {

    ChainBlock block(BlockTag tag);

    /** {@code authorizationState(authorizer, nonce)} at {@code blockNumber}; true once used or cancelled. */
    boolean authorizationState(String authorizer, String nonce, long blockNumber);

    Optional<UsdcReceipt> receipt(String txHash);

    /** The transaction that emitted {@code AuthorizationUsed(authorizer, nonce)}, searched in bounded chunks. */
    Optional<String> findAuthorizationTx(String authorizer, String nonce, long fromBlock, long toBlock);
}
