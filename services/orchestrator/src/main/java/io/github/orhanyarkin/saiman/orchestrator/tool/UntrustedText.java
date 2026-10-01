package io.github.orhanyarkin.saiman.orchestrator.tool;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
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

    /** What replaces a link-like token in display text. */
    public static final String LINK_REMOVED = "[link removed]";

    private static final Pattern DELIMITER = Pattern.compile("tool_data");

    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^\\]]{0,500})\\]\\([^)]{0,2000}\\)");

    /**
     * Link-like tokens, generically: any {@code scheme://}, the script-capable schemes without
     * slashes, protocol-relative {@code //host}, defanged {@code hxxp(s)}, {@code www.}, a bare IPv4
     * address (octets without leading zeros, not part of a longer dotted number), and {@code
     * host.tld} followed by a path, port, query or fragment for any 2+ letter TLD (or a bare host on a
     * common TLD). Dates ({@code 20.06.2016}), amounts ({@code 59.368.579,- Euro}) and abbreviations
     * ({@code A.Ş.}, {@code T.C.}) do not match.
     */
    private static final Pattern LINK = Pattern.compile("(?iu)(?:"
            + "(?:[a-z][a-z0-9+.-]*://|www\\d{0,3}\\.)\\S*"
            + "|(?<![\\p{L}\\p{N}])(?:javascript|data|vbscript|mailto|file|blob|about):\\S+"
            + "|(?<![\\p{L}\\p{N}])hxxps?\\S*"
            + "|(?<![\\p{L}\\p{N}:/])//\\S+"
            + "|(?<![\\p{N}.,])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
            + "(?![\\p{N}]|[.,]\\p{N})\\S*"
            + "|(?<![\\p{L}\\p{N}@.-])(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?\\.)+"
            + "(?:(?:[a-z]{2,24}|xn--[a-z0-9-]{1,59})(?=[/?#:]\\S)"
            + "|(?:com|net|org|io|ai|co|me|xyz|info|biz|app|dev|top|site|online|link|click|ru|cn|tk|tr|uk|de)"
            + "(?![\\p{L}\\p{N}]))\\S*"
            + ")");

    /** Look-alikes of {@code <} and {@code >} that NFKC leaves alone. */
    private static final String LESS_THAN_LIKE = "\u1438\u3008\u02C2\u276C\u276E\u2770\u29FC\u2A79\u2AA6";

    private static final String GREATER_THAN_LIKE = "\u1433\u3009\u02C3\u276D\u276F\u2771\u29FD\u2A7A\u2AA7";

    /** Cyrillic and Greek letters that look like the Latin letters of the delimiter name. */
    private static final String CONFUSABLE_FROM =
            "\u043E\u041E\u03BF\u039F\u0430\u0410\u03B1\u0391\u0442\u0422\u03C4\u03A4"
                    + "\u0501\u04CF\u0406\u0456\u0399\u03B9";

    private static final String CONFUSABLE_TO = "ooooaaaatttt" + "dllili";

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
     * Free text that leaves the process (an event, a report, a model prompt): {@link #clean}, markdown
     * links reduced to their text, link-like tokens replaced by {@value #LINK_REMOVED}, and {@code <},
     * {@code >} and their look-alikes replaced by {@code ‹} and {@code ›} (no tag can be formed).
     * Idempotent.
     */
    public static String forDisplay(String raw, int maxCodePoints) {
        String text = clean(raw, maxCodePoints);
        text = MARKDOWN_LINK.matcher(text).replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
        text = LINK.matcher(text).replaceAll(Matcher.quoteReplacement(LINK_REMOVED));
        text = neutraliseAngles(text);
        return clean(text, maxCodePoints);
    }

    /**
     * Text that will be shown to a model inside a {@code <tool_data>} block: {@link #forDisplay} plus
     * the delimiter name defused, also when spelt with Cyrillic or Greek look-alike letters.
     */
    public static String forModel(String raw, int maxCodePoints) {
        String text = forDisplay(raw, maxCodePoints);
        String skeleton = skeleton(text);
        Matcher matcher = DELIMITER.matcher(skeleton);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        do {
            out.append(text, last, matcher.start()).append("tool-data");
            last = matcher.end();
        } while (matcher.find());
        return out.append(text, last, text.length()).toString();
    }

    private static String neutraliseAngles(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || LESS_THAN_LIKE.indexOf(c) >= 0) {
                out.append('‹');
            } else if (c == '>' || GREATER_THAN_LIKE.indexOf(c) >= 0) {
                out.append('›');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A same-length lower-case Latin skeleton for the delimiter check: one char per char (BMP
     * look-alikes only), so match positions map back to the original text.
     */
    private static String skeleton(String text) {
        char[] chars = new char[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int at = CONFUSABLE_FROM.indexOf(c);
            char mapped = at >= 0 ? CONFUSABLE_TO.charAt(at) : c;
            chars[i] = Character.toLowerCase(mapped) == '\u0131' ? 'i' : Character.toLowerCase(mapped);
        }
        return new String(chars);
    }

    /**
     * A question as a run or a tool accepts it: {@link #forDisplay} (it is shown in events and sent
     * to the seller), {@value #MIN_QUESTION}..{@value #MAX_QUESTION} code points.
     *
     * @return empty if the raw input is too long or the cleaned text is out of bounds
     */
    public static Optional<String> question(String raw) {
        if (raw.length() > MAX_RAW_QUESTION) {
            return Optional.empty();
        }
        String cleaned = forDisplay(raw, MAX_QUESTION + 1);
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
