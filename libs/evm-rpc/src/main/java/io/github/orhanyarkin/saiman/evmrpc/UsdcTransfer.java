package io.github.orhanyarkin.saiman.evmrpc;

/**
 * A USDC {@code Transfer(from, to, value)} log, value in atomic units.
 *
 * @param logIndex the log's index within its block (pairs a transfer with the {@link UsdcAuthorizationUse} of the
 *     same transaction by position); {@value #UNKNOWN_LOG_INDEX} when the node did not report one
 */
public record UsdcTransfer(String from, String to, long value, long logIndex) {

    /** {@link #logIndex()} of a log whose index is not known. */
    public static final long UNKNOWN_LOG_INDEX = -1;

    /** A transfer without a known log index. */
    public UsdcTransfer(String from, String to, long value) {
        this(from, to, value, UNKNOWN_LOG_INDEX);
    }
}
