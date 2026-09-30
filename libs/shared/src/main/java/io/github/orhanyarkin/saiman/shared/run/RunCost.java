package io.github.orhanyarkin.saiman.shared.run;

import io.github.orhanyarkin.saiman.shared.money.Money;

/**
 * Everything one run spent. USDC atomic units and USD micro-dollars both have 6 decimals, so the total
 * is a plain sum: an explicit 1 USDC = 1 USD assumption that holds for testnet USDC (ADR-0013).
 *
 * @param paymentsUsdc settled x402 payments (USDC)
 * @param llmUsd model calls (USD micro-dollars)
 * @param totalUsd the sum, in USD
 */
public record RunCost(Money paymentsUsdc, Money llmUsd, Money totalUsd) {

    public static RunCost of(Money paymentsUsdc, Money llmUsd) {
        if (!"USDC".equals(paymentsUsdc.asset()) || paymentsUsdc.decimals() != Money.SIX_DECIMALS) {
            throw new IllegalArgumentException("payments must be 6-decimal USDC");
        }
        if (!"USD".equals(llmUsd.asset()) || llmUsd.decimals() != Money.SIX_DECIMALS) {
            throw new IllegalArgumentException("llm cost must be 6-decimal USD");
        }
        return new RunCost(
                paymentsUsdc, llmUsd, Money.usdMicros(Math.addExact(paymentsUsdc.atomicUnits(), llmUsd.atomicUnits())));
    }
}
