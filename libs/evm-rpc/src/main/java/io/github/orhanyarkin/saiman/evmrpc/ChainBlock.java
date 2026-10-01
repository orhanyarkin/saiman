package io.github.orhanyarkin.saiman.evmrpc;

/** A block head: number and timestamp (unix seconds). */
public record ChainBlock(long number, long timestamp) {}
