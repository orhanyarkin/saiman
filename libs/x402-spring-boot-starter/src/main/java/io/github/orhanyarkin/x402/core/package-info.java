/**
 * The x402 v2 wire model and codec.
 *
 * <p>Types in this package mirror the x402 v2 specification exactly (field names, optionality)
 * so that {@link io.github.orhanyarkin.x402.core.X402Codec} can round-trip spec examples
 * byte-for-byte. They carry no Spring, HTTP or crypto dependencies. Only the {@code exact} scheme
 * on EVM is modelled: {@link io.github.orhanyarkin.x402.core.ExactEvmPayload} and {@link
 * io.github.orhanyarkin.x402.core.Eip3009Authorization}.
 *
 * <p>Amounts are decimal strings on the wire (see {@link
 * io.github.orhanyarkin.x402.core.PaymentRequirements#amount()}) but {@code long} atomic units
 * everywhere else; use {@link io.github.orhanyarkin.x402.core.AssetAmount} to convert between the
 * two. This starter targets Base Sepolia testnet USDC only; see {@link
 * io.github.orhanyarkin.x402.core.TestnetAssets}.
 */
@NullMarked
package io.github.orhanyarkin.x402.core;

import org.jspecify.annotations.NullMarked;
