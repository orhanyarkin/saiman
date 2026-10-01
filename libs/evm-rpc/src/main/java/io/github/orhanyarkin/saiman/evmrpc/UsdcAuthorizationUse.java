package io.github.orhanyarkin.saiman.evmrpc;

/**
 * A USDC {@code AuthorizationUsed(authorizer, nonce)} log.
 *
 * @param authorizer lower-case {@code 0x} address
 * @param nonce lower-case {@code 0x} + 64 hex characters
 * @param logIndex the log's index within its block ({@value UsdcTransfer#UNKNOWN_LOG_INDEX} if not reported)
 */
public record UsdcAuthorizationUse(String authorizer, String nonce, long logIndex) {

    /** {@code authorizer:nonce}, the form of {@link UsdcReceipt#authorizationsUsed()}. */
    public String key() {
        return authorizer + ":" + nonce;
    }
}
