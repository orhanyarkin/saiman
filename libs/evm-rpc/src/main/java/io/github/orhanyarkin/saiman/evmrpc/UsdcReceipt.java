package io.github.orhanyarkin.saiman.evmrpc;

import java.util.List;

/**
 * The parts of a transaction receipt that concern the USDC contract only.
 *
 * @param succeeded receipt status 1
 * @param authorizationsUsed {@code AuthorizationUsed(authorizer, nonce)} logs, as {@code authorizer:nonce}, lower-case
 */
public record UsdcReceipt(
        String txHash,
        long blockNumber,
        boolean succeeded,
        List<UsdcTransfer> transfers,
        List<String> authorizationsUsed) {

    public UsdcReceipt {
        transfers = List.copyOf(transfers);
        authorizationsUsed = List.copyOf(authorizationsUsed);
    }
}
