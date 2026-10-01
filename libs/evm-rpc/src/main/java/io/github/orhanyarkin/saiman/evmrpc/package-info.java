/**
 * Read-only JSON-RPC access to Base Sepolia for the facts reconciliation needs: block heads, USDC
 * {@code authorizationState}, receipts and bounded log lookups. Fails closed on any other chain (ADR-0018).
 */
@NullMarked
package io.github.orhanyarkin.saiman.evmrpc;

import org.jspecify.annotations.NullMarked;
