package io.github.orhanyarkin.saiman.evmrpc;

/** A USDC {@code Transfer(from, to, value)} log, value in atomic units. */
public record UsdcTransfer(String from, String to, long value) {}
