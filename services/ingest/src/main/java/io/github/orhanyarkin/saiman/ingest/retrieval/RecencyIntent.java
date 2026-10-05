package io.github.orhanyarkin.saiman.ingest.retrieval;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects "give me the newest ..." intent in a question with plain keyword matching (no model call,
 * deterministic, free). Whole words only, so {@code sonuç} and {@code sonra} do not match {@code son}.
 * Case is folded with the Turkish locale ({@code SON}, {@code GÜNCEL}, {@code Yeni}) and, for text typed
 * without diacritics, {@code guncel} is accepted too. {@code Yeni İş İlişkisi} is a real KAP disclosure
 * type, so that phrase is removed before matching.
 */
final class RecencyIntent {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    private static final Set<String> KEYWORDS = Set.of("son", "güncel", "guncel", "yeni", "yenı", "latest", "recent");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DISCLOSURE_TYPE = Pattern.compile("yeni\\s+iş\\s+ilişkisi");

    private RecencyIntent() {}

    static boolean detect(String question) {
        // Turkish-locale lower case maps "I" to dotless "ı", so all-caps ASCII "YENI" becomes "yenı" (listed below).
        String text = DISCLOSURE_TYPE.matcher(question.toLowerCase(TURKISH)).replaceAll(" ");
        for (String word : NON_WORD.split(text)) {
            if (KEYWORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
