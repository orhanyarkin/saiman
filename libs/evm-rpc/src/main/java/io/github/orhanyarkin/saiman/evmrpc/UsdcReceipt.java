package io.github.orhanyarkin.saiman.evmrpc;

import java.util.List;

/**
 * The parts of a transaction receipt that concern the USDC contract only.
 *
 * @param succeeded receipt status 1
 * @param authorizationsUsed {@code AuthorizationUsed(authorizer, nonce)} logs, as {@code authorizer:nonce}, lower-case
 * @param authorizationUses the same logs with their log indices, in log order (a transaction can carry several
 *     authorizations and transfers; the indices pair each {@code AuthorizationUsed} with its {@code Transfer})
 */
public record UsdcReceipt(
        String txHash,
        long blockNumber,
        boolean succeeded,
        List<UsdcTransfer> transfers,
        List<String> authorizationsUsed,
        List<UsdcAuthorizationUse> authorizationUses) {

    public UsdcReceipt {
        transfers = List.copyOf(transfers);
        authorizationsUsed = List.copyOf(authorizationsUsed);
        authorizationUses = List.copyOf(authorizationUses);
    }

    /** A receipt whose authorization logs have no known log index (derived from {@code authorizationsUsed}). */
    public UsdcReceipt(
            String txHash,
            long blockNumber,
            boolean succeeded,
            List<UsdcTransfer> transfers,
            List<String> authorizationsUsed) {
        this(
                txHash,
                blockNumber,
                succeeded,
                transfers,
                authorizationsUsed,
                authorizationsUsed.stream()
                        .map(key -> new UsdcAuthorizationUse(
                                key.substring(0, key.indexOf(':')),
                                key.substring(key.indexOf(':') + 1),
                                UsdcTransfer.UNKNOWN_LOG_INDEX))
                        .toList());
    }
}
