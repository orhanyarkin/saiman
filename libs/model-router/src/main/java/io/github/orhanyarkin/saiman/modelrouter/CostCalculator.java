package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;

/** Token usage times configured prices, in exact integer micro-dollars (CLAUDE.md rule 4). */
public final class CostCalculator {

    private static final long TOKENS_PER_MTOK = 1_000_000L;

    private CostCalculator() {}

    /**
     * Cost of one call: {@code ceil((in * inPrice + out * outPrice) / 1_000_000)}. Rounding up (once
     * per call, not per direction) means the daily counter never under-counts.
     *
     * @throws ArithmeticException on overflow
     * @throws IllegalArgumentException for negative token counts
     */
    public static Money cost(RouterProperties.Price price, long inputTokens, long outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        long numerator = Math.addExact(
                Math.multiplyExact(inputTokens, price.inputUsdMicrosPerMtok()),
                Math.multiplyExact(outputTokens, price.outputUsdMicrosPerMtok()));
        long micros = Math.floorDiv(Math.addExact(numerator, TOKENS_PER_MTOK - 1), TOKENS_PER_MTOK);
        return Money.usdMicros(micros);
    }
}
