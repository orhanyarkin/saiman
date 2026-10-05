package io.github.orhanyarkin.saiman.evals.golden;

import io.github.orhanyarkin.saiman.evals.golden.GoldenSet.Corpus;
import io.github.orhanyarkin.saiman.evals.golden.GoldenSet.Expected;
import io.github.orhanyarkin.saiman.evals.golden.GoldenSet.GoldenItem;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads and validates the golden set. Validation collects every problem with its location
 * ({@code items[3] (R-THYAO).expected.relevant}) so a hand edit is fixed in one pass, and unknown keys are
 * errors (a typo in {@code requiredFacts} must not silently disable a check). SnakeYAML runs with the safe
 * constructor: no arbitrary type construction from the file.
 */
public final class GoldenSetLoader {

    private static final Pattern TICKER = Pattern.compile("[A-Z0-9]{3,6}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_QUESTION_LENGTH = 500;
    private static final int LATEST_SIZE = 5;
    private static final Set<String> ROOT_KEYS = Set.of("version", "corpus", "items");
    private static final Set<String> CORPUS_KEYS = Set.of("watermark", "corpusVersion");
    private static final Set<String> ITEM_KEYS = Set.of("id", "kind", "ticker", "question", "label", "expected");
    private static final Set<String> EXPECTED_KEYS = Set.of("relevant", "latest", "sources", "requiredFacts", "reason");

    private GoldenSetLoader() {}

    /** Loads from a Spring resource location ({@code classpath:...}, {@code file:...} or a plain path). */
    public static GoldenSet load(String location) {
        Resource resource = new DefaultResourceLoader().getResource(location);
        try (InputStream in = resource.getInputStream()) {
            return parse(new java.io.InputStreamReader(in, StandardCharsets.UTF_8), location);
        } catch (IOException e) {
            throw new GoldenSetException("cannot read golden set " + location + ": " + e.getMessage());
        }
    }

    public static GoldenSet parse(String yaml) {
        return parse(new StringReader(yaml), "<string>");
    }

    private static GoldenSet parse(Reader reader, String source) {
        Object root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            root = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (YAMLException e) {
            throw new GoldenSetException("golden set " + source + " is not valid YAML: " + firstLine(e.getMessage()));
        }
        Problems problems = new Problems();
        GoldenSet set = build(root, problems);
        if (set == null || !problems.isEmpty()) {
            throw new GoldenSetException("golden set " + source + " is invalid:\n  - " + problems.join("\n  - "));
        }
        return set;
    }

    private static @Nullable GoldenSet build(@Nullable Object root, Problems problems) {
        Map<String, Object> top = map(root, "<root>", ROOT_KEYS, problems);
        if (top == null) {
            return null;
        }
        Object version = top.get("version");
        if (!(version instanceof Integer v) || v != 1) {
            problems.add("version: must be the integer 1, was " + version);
        }
        Corpus corpus = corpus(top.get("corpus"), problems);
        List<GoldenItem> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (!(top.get("items") instanceof List<?> raw) || raw.isEmpty()) {
            problems.add("items: must be a non-empty list");
        } else {
            for (int i = 0; i < raw.size(); i++) {
                GoldenItem item = item(raw.get(i), i, ids, problems);
                if (item != null) {
                    items.add(item);
                }
            }
        }
        if (corpus == null) {
            return null;
        }
        return new GoldenSet(version instanceof Integer v ? v : 0, corpus, items);
    }

    private static @Nullable Corpus corpus(@Nullable Object raw, Problems problems) {
        Map<String, Object> map = map(raw, "corpus", CORPUS_KEYS, problems);
        if (map == null) {
            return null;
        }
        Instant watermark = instant(map.get("watermark"));
        if (watermark == null) {
            problems.add("corpus.watermark: must be an ISO-8601 instant, was " + map.get("watermark"));
        }
        Object version = map.get("corpusVersion");
        if (version == null || String.valueOf(version).isBlank()) {
            problems.add("corpus.corpusVersion: must not be blank");
        }
        if (watermark == null || version == null) {
            return null;
        }
        return new Corpus(watermark, String.valueOf(version));
    }

    private static @Nullable GoldenItem item(@Nullable Object raw, int index, Set<String> ids, Problems problems) {
        String where = "items[" + index + "]";
        Map<String, Object> map = map(raw, where, ITEM_KEYS, problems);
        if (map == null) {
            return null;
        }
        String id = text(map.get("id"));
        if (id == null || !ID.matcher(id).matches()) {
            problems.add(where + ".id: must match " + ID.pattern() + ", was " + map.get("id"));
            return null;
        }
        where += " (" + id + ")";
        if (!ids.add(id)) {
            problems.add(where + ".id: duplicate");
        }
        Kind kind = null;
        try {
            kind = Kind.valueOf(String.valueOf(map.get("kind")));
        } catch (IllegalArgumentException e) {
            problems.add(
                    where + ".kind: must be one of RETRIEVAL, FRESHNESS, ANSWER, UNANSWERABLE, was " + map.get("kind"));
        }
        String ticker = text(map.get("ticker"));
        if (ticker == null || !TICKER.matcher(ticker).matches()) {
            problems.add(where + ".ticker: must match [A-Z0-9]{3,6}, was " + map.get("ticker"));
        }
        String question = text(map.get("question"));
        if (question == null || question.isBlank() || question.length() > MAX_QUESTION_LENGTH) {
            problems.add(where + ".question: must be 1-" + MAX_QUESTION_LENGTH + " characters");
        }
        Map<String, Object> expectedMap = map(map.get("expected"), where + ".expected", EXPECTED_KEYS, problems);
        if (kind == null || ticker == null || question == null || expectedMap == null) {
            return null;
        }
        Expected expected = expected(kind, expectedMap, where + ".expected", problems);
        return expected == null ? null : new GoldenItem(id, kind, ticker, question, expected);
    }

    private static @Nullable Expected expected(Kind kind, Map<String, Object> map, String where, Problems problems) {
        Map<Long, Integer> relevant = Map.of();
        List<Long> latest = List.of();
        List<Long> sources = List.of();
        List<List<String>> facts = List.of();
        String reason = text(map.get("reason"));
        switch (kind) {
            case RETRIEVAL -> {
                relevant = relevant(map.get("relevant"), where + ".relevant", problems);
                require(map, Set.of("relevant"), where, problems);
            }
            case FRESHNESS -> {
                latest = indexes(map.get("latest"), where + ".latest", problems);
                if (latest.size() != LATEST_SIZE || new HashSet<>(latest).size() != LATEST_SIZE) {
                    problems.add(where + ".latest: must list " + LATEST_SIZE + " distinct disclosure indexes");
                }
                require(map, Set.of("latest"), where, problems);
            }
            case ANSWER -> {
                sources = indexes(map.get("sources"), where + ".sources", problems);
                if (sources.isEmpty()) {
                    problems.add(where + ".sources: must not be empty");
                }
                facts = facts(map.get("requiredFacts"), where + ".requiredFacts", problems);
                require(map, Set.of("sources", "requiredFacts"), where, problems);
            }
            case UNANSWERABLE -> {
                if (reason == null || reason.isBlank()) {
                    problems.add(where + ".reason: must not be blank");
                }
                require(map, Set.of("reason"), where, problems);
            }
        }
        return new Expected(relevant, latest, sources, facts, reason);
    }

    /** Only the keys that belong to the kind may be present: a leftover key from a copy-paste is an error. */
    private static void require(Map<String, Object> map, Set<String> allowed, String where, Problems problems) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                problems.add(where + "." + key + ": not allowed for this kind (allowed: " + allowed + ")");
            }
        }
    }

    private static Map<Long, Integer> relevant(@Nullable Object raw, String where, Problems problems) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            problems.add(where + ": must be a non-empty list of {index, grade}");
            return out;
        }
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> entry = map(list.get(i), where + "[" + i + "]", Set.of("index", "grade"), problems);
            if (entry == null) {
                continue;
            }
            Long index = index(entry.get("index"));
            Object grade = entry.get("grade");
            if (index == null) {
                problems.add(where + "[" + i + "].index: must be a positive integer");
            } else if (!(grade instanceof Integer g) || g < 1 || g > 3) {
                problems.add(where + "[" + i + "].grade: must be 1, 2 or 3, was " + grade);
            } else if (out.put(index, g) != null) {
                problems.add(where + "[" + i + "].index: duplicate " + index);
            }
        }
        return out;
    }

    private static List<Long> indexes(@Nullable Object raw, String where, Problems problems) {
        List<Long> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            problems.add(where + ": must be a list of disclosure indexes");
            return out;
        }
        for (Object value : list) {
            Long index = index(value);
            if (index == null) {
                problems.add(where + ": not a positive integer: " + value);
            } else {
                out.add(index);
            }
        }
        return out;
    }

    private static List<List<String>> facts(@Nullable Object raw, String where, Problems problems) {
        List<List<String>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            problems.add(where + ": must be a non-empty list of {anyOf: [...]}");
            return out;
        }
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> entry = map(list.get(i), where + "[" + i + "]", Set.of("anyOf"), problems);
            if (entry == null) {
                continue;
            }
            if (entry.get("anyOf") instanceof List<?> options
                    && !options.isEmpty()
                    && options.stream().allMatch(o -> o instanceof String s && !s.isBlank())) {
                out.add(options.stream().map(String::valueOf).toList());
            } else {
                problems.add(where + "[" + i + "].anyOf: must be a non-empty list of non-blank strings");
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> map(
            @Nullable Object raw, String where, Set<String> allowedKeys, Problems problems) {
        if (!(raw instanceof Map<?, ?> m)) {
            problems.add(where + ": must be a mapping");
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : m.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!allowedKeys.contains(key)) {
                problems.add(where + "." + key + ": unknown key (known: " + allowedKeys + ")");
            }
            out.put(key, entry.getValue());
        }
        return out;
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static @Nullable Long index(@Nullable Object value) {
        if (value instanceof Integer i && i > 0) {
            return i.longValue();
        }
        if (value instanceof Long l && l > 0) {
            return l;
        }
        return null;
    }

    private static @Nullable Instant instant(@Nullable Object value) {
        if (value instanceof Date date) { // SnakeYAML resolves unquoted ISO timestamps to java.util.Date
            return date.toInstant();
        }
        if (value instanceof String s) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    private static String firstLine(@Nullable String message) {
        if (message == null) {
            return "unknown error";
        }
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }

    private static final class Problems {
        private final List<String> problems = new ArrayList<>();

        void add(String problem) {
            problems.add(problem);
        }

        boolean isEmpty() {
            return problems.isEmpty();
        }

        String join(String separator) {
            return String.join(separator, problems);
        }
    }
}
