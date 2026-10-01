package io.github.orhanyarkin.saiman.evmrpc;

/** Block tags the client reads at. Decisions that release budget or declare a mismatch use {@link #SAFE}. */
public enum BlockTag {
    LATEST,
    SAFE
}
