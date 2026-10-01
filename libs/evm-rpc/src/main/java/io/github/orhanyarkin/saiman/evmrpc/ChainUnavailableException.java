package io.github.orhanyarkin.saiman.evmrpc;

/** The RPC endpoint could not answer (timeout, 429, 5xx, malformed). Callers skip the item; never a mismatch. */
public class ChainUnavailableException extends RuntimeException {

    public ChainUnavailableException(String message) {
        super(message);
    }
}
