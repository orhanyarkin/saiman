/**
 * EIP-712 typed-data hashing and EIP-3009 signing/recovery for the {@code exact} scheme on EVM.
 *
 * <p>Built on {@code org.web3j:crypto} (secp256k1, Keccak, EIP-712; ADR-0008), scoped to the
 * single fixed domain in {@link io.github.orhanyarkin.x402.core.TestnetAssets}: there is no
 * network or asset parameter anywhere in this package.
 */
@NullMarked
package io.github.orhanyarkin.x402.evm;

import org.jspecify.annotations.NullMarked;
