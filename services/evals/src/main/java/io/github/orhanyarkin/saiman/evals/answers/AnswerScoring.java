package io.github.orhanyarkin.saiman.evals.answers;

import io.github.orhanyarkin.saiman.evals.metrics.Disclosures;
import io.github.orhanyarkin.saiman.shared.eval.EvalOutcome;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic, judge-free scoring of an answer (ADR-0025, ADR-0003: no LLM judge in M6). Every method is a
 * pure function; the hand-computed cases are in {@code AnswerScoringTests}.
 *
 * <p>Definitions:
 *
 * <ul>
 *   <li><b>Citation valid</b>: the chunk id is well formed ({@code kap:<index>:<nnnn>}) and its disclosure index is
 *       in the allowed set. The allowed set is the disclosures that retrieval returns for the same question and
 *       ticker (top-k as configured); when that retrieval fails, it falls back to every disclosure index that
 *       appears anywhere in the golden set (a real indexed disclosure, but not necessarily one retrieval would
 *       surface). The seller already drops citations that are not among its own retrieved chunks, so this is an
 *       independent re-check of the same property.
 *   <li><b>Citation recall</b> (ANSWER): share of {@code expected.sources} whose disclosure is cited.
 *   <li><b>Fact recall</b> (ANSWER): share of {@code requiredFacts} entries for which at least one of the
 *       acceptable spellings occurs in the normalised answer.
 *   <li><b>Refusal correct</b> (UNANSWERABLE): outcome {@code REFUSED} or {@code NO_VALID_CITATIONS}. An
 *       {@code ANSWERED} outcome is wrong; {@code LLM_CAP} and {@code ERROR} are not scored.
 * </ul>
 */
public final class AnswerScoring {

    private static final String[] MONTHS = {
        "ocak", "subat", "mart", "nisan", "mayis", "haziran", "temmuz", "agustos", "eylul", "ekim", "kasim", "aralik"
    };
    private static final Locale TR = Locale.of("tr", "TR");
    private static final Pattern NUMERIC_DATE = Pattern.compile("(?<!\\d)(\\d{1,2})[./](\\d{1,2})[./](\\d{4})(?!\\d)");
    private static final Pattern ISO_DATE = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)");
    private static final Pattern LONG_DATE =
            Pattern.compile("(?<!\\d)(\\d{1,2})\\s+(" + String.join("|", MONTHS) + ")\\w*\\s+(\\d{4})(?!\\d)");
    private static final Pattern PERCENT_PREFIX = Pattern.compile("%\\s*(\\d+(?:[.,]\\d+)?)");
    private static final Pattern PERCENT_WORD = Pattern.compile("yuzde\\s*(\\d+(?:[.,]\\d+)?)");
    private static final Pattern PERCENT_SUFFIX = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*%");
    private static final Pattern DECIMAL_COMMA_PERCENT = Pattern.compile("(\\d+),(\\d+)%");

    private AnswerScoring() {}

    /**
     * Case, diacritic, date and percent normalisation applied to both the answer and the expected spellings.
     * Turkish-locale lower-casing first (so {@code I} becomes the dotless {@code i}), then diacritics are
     * folded, dates become {@code yyyy-MM-dd} ({@code 15.11.2023}, {@code 15/11/2023}, {@code 15 Kasım 2023}) and percentages become {@code 12.5%} ({@code %12,5},
     * {@code yüzde 12,5}, {@code 12,5 %}).
     */
    public static String normalize(String text) {
        String s = text.toLowerCase(TR)
                .replace('ı', 'i')
                .replace('ş', 's')
                .replace('ğ', 'g')
                .replace('ç', 'c')
                .replace('ö', 'o')
                .replace('ü', 'u')
                .replace('â', 'a')
                .replace('î', 'i')
                .replace('û', 'u')
                .replace(' ', ' ');
        s = replaceDates(NUMERIC_DATE, s, m -> iso(m.group(3), m.group(2), m.group(1)));
        s = replaceDates(LONG_DATE, s, m -> iso(m.group(3), String.valueOf(monthNumber(m.group(2))), m.group(1)));
        s = replaceDates(ISO_DATE, s, m -> iso(m.group(1), m.group(2), m.group(3)));
        s = PERCENT_PREFIX.matcher(s).replaceAll("$1%");
        s = PERCENT_WORD.matcher(s).replaceAll("$1%");
        s = PERCENT_SUFFIX.matcher(s).replaceAll("$1%");
        s = DECIMAL_COMMA_PERCENT.matcher(s).replaceAll("$1.$2%");
        return s.replaceAll("\\s+", " ").strip();
    }

    /** True when any of the acceptable spellings occurs in the answer (after normalisation, on digit boundaries). */
    public static boolean factMatched(String answer, List<String> anyOf) {
        String normalized = normalize(answer);
        for (String spelling : anyOf) {
            String wanted = normalize(spelling);
            if (wanted.isEmpty()) {
                continue;
            }
            String pattern = (Character.isDigit(wanted.charAt(0)) ? "(?<!\\d)(?<!\\d[.,])" : "")
                    + Pattern.quote(wanted)
                    + (Character.isDigit(wanted.charAt(wanted.length() - 1)) ? "(?!\\d)" : "");
            if (Pattern.compile(pattern).matcher(normalized).find()) {
                return true;
            }
        }
        return false;
    }

    /** Matched entries divided by entries; 1.0 when the item requires no facts. */
    public static double factRecall(String answer, List<List<String>> requiredFacts) {
        if (requiredFacts.isEmpty()) {
            return 1.0;
        }
        long matched =
                requiredFacts.stream().filter(f -> factMatched(answer, f)).count();
        return (double) matched / requiredFacts.size();
    }

    /** Well-formed {@code kap:<index>:<nnnn>} and the index is in {@code allowedIndexes}. */
    public static boolean citationValid(String chunkId, Set<Long> allowedIndexes) {
        try {
            return allowedIndexes.contains(Disclosures.indexOf(chunkId));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** Number of citations that are valid; duplicates count each time. */
    public static int validCitations(Collection<String> chunkIds, Set<Long> allowedIndexes) {
        return (int) chunkIds.stream()
                .filter(id -> citationValid(id, allowedIndexes))
                .count();
    }

    /** Share of the expected source disclosures that are cited (malformed ids cite nothing); 1.0 for no sources. */
    public static double citationRecall(List<Long> expectedSources, Collection<String> citedChunkIds) {
        if (expectedSources.isEmpty()) {
            return 1.0;
        }
        Set<Long> cited = new HashSet<>();
        for (String id : citedChunkIds) {
            try {
                cited.add(Disclosures.indexOf(id));
            } catch (IllegalArgumentException ignored) {
                // a malformed id cites no disclosure
            }
        }
        long hit = expectedSources.stream().filter(cited::contains).count();
        return (double) hit / expectedSources.size();
    }

    /** True / false for a scored outcome of an UNANSWERABLE question, null when the outcome says nothing. */
    @Nullable
    public static Boolean refusalCorrect(EvalOutcome outcome) {
        return switch (outcome) {
            case REFUSED, NO_VALID_CITATIONS -> Boolean.TRUE;
            case ANSWERED -> Boolean.FALSE;
            case LLM_CAP, ERROR -> null;
        };
    }

    /** Integer micro-USD mean, rounded half up; 0 for no questions. */
    public static long meanMicros(long totalMicros, int questions) {
        return questions <= 0 ? 0 : (totalMicros + questions / 2) / questions;
    }

    /** Report-edge formatting of micro-USD, e.g. {@code 1234} to {@code $0.001234}. */
    public static String formatUsd(long micros) {
        return String.format(Locale.ROOT, "$%d.%06d", micros / 1_000_000, micros % 1_000_000);
    }

    private static String iso(String year, String month, String day) {
        int m = Integer.parseInt(month);
        int d = Integer.parseInt(day);
        if (m < 1 || m > 12 || d < 1 || d > 31) {
            return year + "-" + month + "-" + day + "?"; // not a date; keep it from matching anything
        }
        return String.format(Locale.ROOT, "%s-%02d-%02d", year, m, d);
    }

    private static int monthNumber(String name) {
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(name)) {
                return i + 1;
            }
        }
        throw new IllegalStateException("regex admitted an unknown month: " + name);
    }

    private static String replaceDates(Pattern pattern, String text, Function<MatchResult, String> replacement) {
        return pattern.matcher(text).replaceAll(m -> Matcher.quoteReplacement(replacement.apply(m)));
    }
}
