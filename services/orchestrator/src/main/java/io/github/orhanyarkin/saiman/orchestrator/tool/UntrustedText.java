package io.github.orhanyarkin.saiman.orchestrator.tool;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Hygiene for text that came from outside (a user's question, a model's tool argument, a seller's
 * answer). Deterministic and allowlist-shaped: what survives is printable text on one line.
 *
 * <ol>
 *   <li>NFKC normalisation first, so compatibility forms (fullwidth {@code ＜}, small {@code ﹤}) turn
 *       into the characters the later steps look for;
 *   <li>line breaks, tabs and other whitespace become a single space;
 *   <li>controls (C0 and C1), format characters (including the bidi overrides U+202A-202E and
 *       isolates U+2066-2069, zero-width characters and the Unicode tag block), surrogates,
 *       private-use and unassigned code points are dropped, and so are invisible characters filed
 *       under other categories (variation selectors, the combining grapheme joiner, Hangul fillers,
 *       Khmer inherent vowels, the braille blank); a run of more than {@value #MAX_COMBINING_RUN}
 *       non-spacing marks is cut to {@value #MAX_COMBINING_RUN};
 *   <li>runs of spaces collapse, the ends are trimmed, and the result is capped by code points (a
 *       surrogate pair is never split).
 * </ol>
 */
public final class UntrustedText {

    /** Longest question a run or a tool call accepts, in code points after cleaning. */
    public static final int MAX_QUESTION = 500;

    public static final int MIN_QUESTION = 3;

    /** Raw input above this is refused before any work is done on it. */
    private static final int MAX_RAW_QUESTION = 4 * MAX_QUESTION;

    /** Longest run of consecutive non-spacing marks kept ("zalgo" text is capped, not refused). */
    static final int MAX_COMBINING_RUN = 3;

    private static final Pattern DELIMITER = Pattern.compile("tool_data", Pattern.CASE_INSENSITIVE);

    private UntrustedText() {}

    /** Cleans {@code raw} as described above and caps it at {@code maxCodePoints}. */
    public static String clean(String raw, int maxCodePoints) {
        String normalised = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(Math.min(normalised.length(), maxCodePoints * 2));
        int kept = 0;
        int marks = 0;
        boolean pendingSpace = false;
        for (int i = 0; i < normalised.length() && kept < maxCodePoints; ) {
            int cp = normalised.codePointAt(i);
            i += Character.charCount(cp);
            if (isSpace(cp)) {
                pendingSpace = kept > 0;
                continue;
            }
            if (isDropped(cp)) {
                continue;
            }
            if (Character.getType(cp) == Character.NON_SPACING_MARK) {
                if (++marks > MAX_COMBINING_RUN) {
                    continue;
                }
            } else {
                marks = 0;
            }
            if (pendingSpace) {
                if (kept + 1 >= maxCodePoints) {
                    break;
                }
                out.append(' ');
                kept++;
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
            kept++;
        }
        return out.toString();
    }

    /**
     * Cleans text that will be shown to a model inside a {@code <tool_data>} block: {@link #clean}
     * plus {@code <} and {@code >} replaced by {@code ‹} and {@code ›} (no tag can be formed), and the
     * delimiter name itself defused.
     */
    public static String forModel(String raw, int maxCodePoints) {
        String cleaned = clean(raw, maxCodePoints).replace('<', '‹').replace('>', '›');
        return DELIMITER.matcher(cleaned).replaceAll("tool-data");
    }

    /**
     * A question as a run or a tool accepts it: cleaned, {@value #MIN_QUESTION}..{@value
     * #MAX_QUESTION} code points.
     *
     * @return empty if the raw input is too long or the cleaned text is out of bounds
     */
    public static Optional<String> question(String raw) {
        if (raw.length() > MAX_RAW_QUESTION) {
            return Optional.empty();
        }
        String cleaned = clean(raw, MAX_QUESTION + 1);
        int length = cleaned.codePointCount(0, cleaned.length());
        if (length < MIN_QUESTION || length > MAX_QUESTION) {
            return Optional.empty();
        }
        return Optional.of(cleaned);
    }

    /** A ticker as the seller accepts it ({@code [A-Z0-9]{3,6}}), upper-cased; empty otherwise. */
    public static Optional<String> ticker(String raw) {
        String upper = raw.strip().toUpperCase(Locale.ROOT);
        return ToolArguments.TICKER.matcher(upper).matches() ? Optional.of(upper) : Optional.empty();
    }

    private static boolean isSpace(int cp) {
        // U+0085 (NEL) is a line break but a control character to Character.isWhitespace.
        return cp == 0x85 || Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    /**
     * Characters dropped on top of the general categories below: invisible or filler characters that
     * Unicode files as marks or letters, so a category check alone keeps them.
     */
    static boolean isInvisible(int cp) {
        return (cp >= 0xFE00 && cp <= 0xFE0F) // variation selectors
                || (cp >= 0xE0100 && cp <= 0xE01EF) // variation selectors supplement
                || (cp >= 0x180B && cp <= 0x180F) // Mongolian free variation selectors and vowel separator
                || cp == 0x034F // combining grapheme joiner
                || cp == 0x115F
                || cp == 0x1160
                || cp == 0x3164
                || cp == 0xFFA0 // Hangul fillers
                || cp == 0x17B4
                || cp == 0x17B5 // Khmer inherent vowels
                || cp == 0x2800; // braille pattern blank
    }

    private static boolean isDropped(int cp) {
        if (isInvisible(cp)) {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONTROL,
                    Character.FORMAT,
                    Character.SURROGATE,
                    Character.PRIVATE_USE,
                    Character.UNASSIGNED -> true;
            default -> false;
        };
    }
}
