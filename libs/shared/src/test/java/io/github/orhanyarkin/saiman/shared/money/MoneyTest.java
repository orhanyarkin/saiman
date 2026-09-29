package io.github.orhanyarkin.saiman.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void addsAndSubtractsInAtomicUnits() {
        Money price = Money.usdc(10_000);
        assertThat(price.plus(Money.usdc(20_000))).isEqualTo(Money.usdc(30_000));
        assertThat(Money.usdc(30_000).minus(price)).isEqualTo(Money.usdc(20_000));
        assertThat(price.zero().isZero()).isTrue();
    }

    @Test
    void rejectsNegativeResultsAndValues() {
        assertThatThrownBy(() -> Money.usdc(1).minus(Money.usdc(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.usdc(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowIsAnErrorNotAWraparound() {
        assertThatThrownBy(() -> Money.usdc(Long.MAX_VALUE).plus(Money.usdc(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void differentAssetsNeverCombine() {
        assertThatThrownBy(() -> Money.usdc(1).plus(Money.usdMicros(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.usdc(1).compareTo(Money.usdMicros(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparesWithinOneAsset() {
        assertThat(Money.usdMicros(700_000).isGreaterThan(Money.usdMicros(1))).isTrue();
        assertThat(Money.usdMicros(1).isGreaterThan(Money.usdMicros(1))).isFalse();
    }

    @Test
    void validatesAssetAndDecimals() {
        assertThatThrownBy(() -> new Money(1, "usdc", 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(1, "USDC", 19)).isInstanceOf(IllegalArgumentException.class);
    }
}
