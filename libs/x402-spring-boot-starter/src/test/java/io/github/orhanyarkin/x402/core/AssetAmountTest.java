package io.github.orhanyarkin.x402.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AssetAmountTest {

    @Test
    void parsesZero() {
        assertThat(AssetAmount.parse("0").atomicUnits()).isZero();
    }

    @Test
    void parsesTheSpecExampleAmount() {
        assertThat(AssetAmount.parse("10000")).isEqualTo(new AssetAmount(10_000L));
    }

    @Test
    void parsesLongMaxValue() {
        assertThat(AssetAmount.parse(Long.toString(Long.MAX_VALUE)).atomicUnits())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void toWireValueRoundTrips() {
        AssetAmount amount = new AssetAmount(1_000_000L);
        assertThat(AssetAmount.parse(amount.toWireValue())).isEqualTo(amount);
    }

    @Test
    void constructorRejectsNegativeAtomicUnits() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AssetAmount(-1L));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsNullOrEmpty(String wireValue) {
        assertThatIllegalArgumentException().isThrownBy(() -> AssetAmount.parse(wireValue));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "-1", // negative sign
                "+1", // leading plus
                "1.0", // decimal point
                "1e10", // exponent notation
                "01", // leading zero
                "007", // leading zeros
                "1_000", // separators are a Java-ism, not wire-legal
                "0x10", // hex
                "abc", // not a number at all
                " 10", // leading whitespace
                "10 ", // trailing whitespace
                "10000000000000000000" // fits in decimal digits but overflows a signed long
            })
    void rejectsMalformedOrOverflowingAmounts(String wireValue) {
        assertThatThrownBy(() -> AssetAmount.parse(wireValue)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOneBeyondLongMaxValue() {
        String oneTooMany = java.math.BigInteger.valueOf(Long.MAX_VALUE)
                .add(java.math.BigInteger.ONE)
                .toString();
        assertThatThrownBy(() -> AssetAmount.parse(oneTooMany)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectionMessagesNeverEchoTheWireValue() {
        // A marker containing characters that would be dangerous to echo verbatim into a log
        // line (CR/LF injection) as well as being simply malformed.
        String malicious = "10000\r\nX-Injected: true";
        assertThatThrownBy(() -> AssetAmount.parse(malicious))
                .satisfies(e ->
                        assertThat(e.getMessage()).doesNotContain(malicious).doesNotContain("X-Injected"));

        String overflowing = "99999999999999999999999999999999999999999999999999999999999999999999999999999999999999";
        assertThatThrownBy(() -> AssetAmount.parse(overflowing))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(overflowing));
    }
}
