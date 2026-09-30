package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CostCalculatorTests {

    private record Case(long inPrice, long outPrice, long in, long out, long expectedMicros) {}

    @Test
    void costIsExactAndRoundedUpOncePerCall() {
        List<Case> table = List.of(
                new Case(100_000, 500_000, 0, 0, 0),
                new Case(100_000, 500_000, 1_000_000, 0, 100_000),
                new Case(100_000, 500_000, 0, 1_000_000, 500_000),
                new Case(100_000, 500_000, 1_000_000, 1_000_000, 600_000),
                // 1 token at 0.1 USD/MTok is 0.1 micro-dollar: rounds up to 1, never down to 0
                new Case(100_000, 500_000, 1, 0, 1),
                // both directions are summed before rounding: 0.1 + 0.5 -> 1, not 1 + 1
                new Case(100_000, 500_000, 1, 1, 1),
                // 12345 * 0.2 + 6789 * 1.2 = 2469 + 8146.8 = 10615.8 -> 10616
                new Case(200_000, 1_200_000, 12_345, 6_789, 10_616),
                new Case(0, 0, 5_000_000, 5_000_000, 0),
                new Case(20_000, 0, 1_536_000, 0, 30_720));
        for (Case c : table) {
            var price = new RouterProperties.Price(c.inPrice, c.outPrice);
            assertThat(CostCalculator.cost(price, c.in, c.out).atomicUnits())
                    .as("%s", c)
                    .isEqualTo(c.expectedMicros);
        }
    }

    @Test
    void costIsUsdInSixDecimals() {
        var money = CostCalculator.cost(new RouterProperties.Price(1, 1), 1, 1);
        assertThat(money.asset()).isEqualTo("USD");
        assertThat(money.decimals()).isEqualTo(6);
    }

    @Test
    void rejectsNegativeTokensAndOverflow() {
        var price = new RouterProperties.Price(1_000_000, 1_000_000);
        assertThatThrownBy(() -> CostCalculator.cost(price, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CostCalculator.cost(price, Long.MAX_VALUE, 0)).isInstanceOf(ArithmeticException.class);
    }
}
