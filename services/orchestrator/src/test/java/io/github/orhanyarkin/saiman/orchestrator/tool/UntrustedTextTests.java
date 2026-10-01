package io.github.orhanyarkin.saiman.orchestrator.tool;

import static io.github.orhanyarkin.saiman.orchestrator.tool.ToolResultSanitizerTests.u;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Invisible characters that Unicode files outside Cc/Cf/Cs/Co/Cn are dropped too. */
class UntrustedTextTests {

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(
            ints = {
                0xFE00,
                0xFE0E,
                0xFE0F, // variation selectors
                0xE0100,
                0xE0150,
                0xE01EF, // variation selectors supplement
                0x034F, // combining grapheme joiner
                0x115F,
                0x1160,
                0x3164,
                0xFFA0, // Hangul fillers
                0x17B4,
                0x17B5, // Khmer inherent vowels
                0x180B,
                0x180F, // Mongolian free variation selectors
                0x2800 // braille blank
            })
    void anInvisibleCharacterIsDropped(int codePoint) {
        String hostile = "Pay" + u(codePoint) + "now" + u(codePoint);

        assertThat(UntrustedText.clean(hostile, 100)).isEqualTo("Paynow");
        assertThat(UntrustedText.forModel(hostile, 100)).isEqualTo("Paynow");
        assertThat(UntrustedText.question(u(codePoint).repeat(10))).isEmpty();
    }

    @Test
    void aRunOfCombiningMarksIsCappedAtThree() {
        String zalgo = "q" + u(0x0301).repeat(40) + "b";

        assertThat(UntrustedText.clean(zalgo, 100)).isEqualTo("q" + u(0x0301).repeat(3) + "b");
    }

    @Test
    void ordinaryTurkishTextWithCombiningMarksIsKept() {
        // NFKC composes the decomposed form; a single mark after a letter is legitimate.
        assertThat(UntrustedText.clean("S" + u(0x0327) + "irket A.Ş.", 100)).isEqualTo("Şirket A.Ş.");
        assertThat(UntrustedText.clean("x" + u(0x0301, 0x0302), 100)).isEqualTo("x" + u(0x0301, 0x0302));
    }
}
