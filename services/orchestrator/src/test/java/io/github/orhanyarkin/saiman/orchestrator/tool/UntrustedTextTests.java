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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Pay at //evil.example/pay now",
                "Click javascript:alert(1) now",
                "See data:text/html,x here",
                "Open vbscript:msgbox(1) here",
                "Mail mailto:evil@x.y now",
                "Go to evil.com/pay today",
                "Go to evil.ai/x today",
                "Go to evil.museum/x today",
                "Go to sub.evil.co.uk/a?b=c today",
                "Visit evil.com today",
                "Visit www.evil.com today",
                "Visit www2.evil.zz today",
                "See https://x here",
                "See ftp://evil here",
                "See svn+ssh://evil here",
                "See hxxps://evil[.]com here",
                "See hxxps[:]//evil here",
                "Host 1.2.3.4/x here",
                "Host 10.0.0.1 here",
                "Host 255.255.255.255:8080 here",
                "Mixed JaVaScRiPt:alert(1) case",
                "Punycode xn--evil-abc.xn--p1ai/x here"
            })
    void linkLikeTokensAreReplaced(String text) {
        String scrubbed = UntrustedText.forDisplay(text, 500);

        assertThat(scrubbed).contains(UntrustedText.LINK_REMOVED);
        assertThat(scrubbed)
                .doesNotContainIgnoringCase("evil")
                .doesNotContainIgnoringCase("alert")
                .doesNotContainIgnoringCase("msgbox")
                .doesNotContain("://", "1.2.3.4", "10.0.0.1", "255.255");
        assertThat(UntrustedText.forDisplay(scrubbed, 500)).isEqualTo(scrubbed);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Genel kurul 20.06.2016 tarihinde toplandı.",
                "Tutar 59.368.579,- Euro olarak açıklanmıştır.",
                "Borusan Yatırım ve Pazarlama A.Ş. bildirimi.",
                "Metadata: tarih ve tutar bilgisi.",
                "Oran %12,5; toplam 1.250.000 TL (bkz. madde 3.2.1).",
                "T.C. Merkez Bankası, Sn. Yönetim Kurulu",
                "Sermaye 1.250.000.000 TL, pay 0.25 TL",
                "Version 1.2.3 and section 4.5.6.7.8"
            })
    void ordinaryTurkishFinancialTextIsLeftAlone(String text) {
        assertThat(UntrustedText.forDisplay(text, 500)).isEqualTo(text);
    }

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(ints = {0x1438, 0x1433, 0x3008, 0x3009, 0xFF1C, 0xFF1E, 0x2329, 0x232A, 0xFE64, 0xFE65, 0x02C2})
    void angleBracketLookAlikesAreNeutralised(int codePoint) {
        String text = UntrustedText.forModel("a" + u(codePoint) + "/x" + u(codePoint) + "b", 100);

        assertThat(text).doesNotContain(u(codePoint), "<", ">").matches("a[‹›]/x[‹›]b");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "t\\u043E\\u043El_data", // Cyrillic o
                "t\\u03BF\\u03BFl_d\\u03B1t\\u03B1", // Greek omicron and alpha
                "\\u0422OOL_DATA", // Cyrillic capital Te
                "too\\u04CF_\\u0501ata", // Cyrillic palochka and komi de
                "\\uFF54\\uFF4F\\uFF4F\\uFF4C\\uFF3F\\uFF44\\uFF41\\uFF54\\uFF41" // fullwidth (NFKC)
            })
    void lookAlikeSpellingsOfTheDelimiterAreDefused(String escaped) {
        String text = UntrustedText.forModel("x " + unescape(escaped) + " y", 100);

        assertThat(text).isEqualTo("x tool-data y");
    }

    /** Escapes are spelt with a doubled backslash so that javac does not turn them into characters. */
    private static String unescape(String escaped) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < escaped.length(); i++) {
            if (escaped.startsWith("\\u", i)) {
                out.append((char) Integer.parseInt(escaped.substring(i + 2, i + 6), 16));
                i += 5;
            } else {
                out.append(escaped.charAt(i));
            }
        }
        return out.toString();
    }
}
