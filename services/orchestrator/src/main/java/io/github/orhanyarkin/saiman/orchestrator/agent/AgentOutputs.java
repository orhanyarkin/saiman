package io.github.orhanyarkin.saiman.orchestrator.agent;

import io.github.orhanyarkin.saiman.orchestrator.run.FailureCode;
import io.github.orhanyarkin.saiman.orchestrator.tool.EvidenceCitation;
import io.github.orhanyarkin.saiman.orchestrator.tool.UntrustedText;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict parsing and validation of the agents' structured answers. A model answer is untrusted: it
 * must be one JSON object with exactly the expected fields of the expected types (duplicate keys
 * refused), and every value is checked by code. Malformed output is {@link
 * FailureCode#INVALID_MODEL_OUTPUT}; a well-formed plan that breaks a rule is {@link
 * FailureCode#INVALID_PLAN}; an answer without one valid citation is {@link
 * FailureCode#NO_VALID_CITATIONS}.
 */
final class AgentOutputs {

    static final int MAX_TICKERS = 3;
    static final int MAX_TASKS = 4;
    static final int MAX_TASK_CHARS = 200;
    static final int MAX_RISKS = 5;
    static final int MAX_RISK_TITLE = 200;
    static final int MAX_CITATIONS = 10;
    static final int MAX_CITED_IDS = 50;
    static final int MAX_ANSWER = 4_000;
    static final int MAX_NOTES = 4_000;
    static final int MAX_OUTPUT_CHARS = 32_000;

    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Pattern FENCE = Pattern.compile("(?s)^```[a-zA-Z]*\\s*(.*?)\\s*```$");
    private static final Pattern CHUNK_ID = Pattern.compile("kap:(\\d{1,10}):\\d{4}");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^\\]]{0,500})\\]\\([^)]{0,2000}\\)");
    private static final Pattern LINK = Pattern.compile(
            "(?i)\\b(?:https?|ftp|file|data|javascript|vbscript|mailto):\\S+|\\bwww\\.\\S+|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|net|org|io|tr|xyz|info|ru)(?:/\\S*)?");
    private static final String LINK_REMOVED = "[link removed]";
    private static final String KAP_PAGE = "https://www.kap.org.tr/tr/Bildirim/";

    /** Why a model answer was refused; carries only a fixed code. */
    static final class OutputException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final FailureCode code;

        OutputException(FailureCode code) {
            super(code.name());
            this.code = code;
        }

        FailureCode code() {
            return code;
        }
    }

    /** A validated plan: known tickers, cleaned tasks. */
    record Plan(List<String> tickers, List<String> tasks) {}

    /** A validated risk: cleaned title, fixed severity, chunk ids present in the evidence. */
    record Risk(String title, String severity, List<String> chunkIds) {}

    private final ObjectReader reader;

    AgentOutputs(JsonMapper json) {
        this.reader = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    /**
     * The planner's answer, validated against the seller's catalogue: 1..{@value #MAX_TICKERS}
     * distinct catalogue tickers and 1..{@value #MAX_TASKS} non-empty tasks.
     */
    Plan plan(@Nullable String text, Set<String> catalogue) {
        JsonNode root = object(text, Set.of("tickers", "tasks"));
        List<String> rawTickers = strings(root.get("tickers"));
        List<String> rawTasks = strings(root.get("tasks"));
        if (rawTickers.isEmpty() || rawTickers.size() > MAX_TICKERS) {
            throw new OutputException(FailureCode.INVALID_PLAN);
        }
        Set<String> tickers = new LinkedHashSet<>();
        for (String raw : rawTickers) {
            String ticker = UntrustedText.ticker(raw).orElse(null);
            if (ticker == null || !catalogue.contains(ticker) || !tickers.add(ticker)) {
                throw new OutputException(FailureCode.INVALID_PLAN);
            }
        }
        if (rawTasks.isEmpty() || rawTasks.size() > MAX_TASKS) {
            throw new OutputException(FailureCode.INVALID_PLAN);
        }
        List<String> tasks = new ArrayList<>();
        for (String raw : rawTasks) {
            String task = scrub(raw, MAX_TASK_CHARS);
            if (task.isEmpty()) {
                throw new OutputException(FailureCode.INVALID_PLAN);
            }
            tasks.add(task);
        }
        return new Plan(List.copyOf(tickers), List.copyOf(tasks));
    }

    /**
     * The risk step's answer: at most {@value #MAX_RISKS} risks (extra ones dropped), each with a
     * cleaned title, a fixed severity and only chunk ids that the evidence holds.
     */
    List<Risk> risks(@Nullable String text, Function<String, Optional<EvidenceCitation>> evidence) {
        JsonNode root = object(text, Set.of("risks"));
        JsonNode array = root.get("risks");
        if (array == null || !array.isArray()) {
            throw new OutputException(FailureCode.INVALID_MODEL_OUTPUT);
        }
        List<Risk> risks = new ArrayList<>();
        for (JsonNode node : array) {
            if (risks.size() >= MAX_RISKS) {
                break;
            }
            if (!node.isObject() || !fields(node).equals(Set.of("title", "severity", "citedChunkIds"))) {
                throw new OutputException(FailureCode.INVALID_MODEL_OUTPUT);
            }
            String title = scrub(string(node.get("title")), MAX_RISK_TITLE);
            String severity = string(node.get("severity")).strip().toUpperCase(Locale.ROOT);
            if (title.isEmpty() || !SEVERITIES.contains(severity)) {
                throw new OutputException(FailureCode.INVALID_MODEL_OUTPUT);
            }
            List<String> ids = validIds(strings(node.get("citedChunkIds")), evidence);
            risks.add(new Risk(title, severity, ids));
        }
        return List.copyOf(risks);
    }

    /**
     * The synthesis step's answer as the report: the answer cleaned and link-scrubbed, the citations
     * rebuilt by code from the run's evidence for the cited ids that it holds.
     *
     * @throws OutputException {@code NO_VALID_CITATIONS} if no cited id is in the evidence
     */
    RunEventData.Report report(@Nullable String text, Function<String, Optional<EvidenceCitation>> evidence) {
        JsonNode root = object(text, Set.of("answer", "citedChunkIds"));
        String answer = scrub(string(root.get("answer")), MAX_ANSWER);
        List<String> cited = strings(root.get("citedChunkIds"));
        if (answer.isEmpty()) {
            throw new OutputException(FailureCode.INVALID_MODEL_OUTPUT);
        }
        List<RunEventData.Citation> citations = new ArrayList<>();
        for (String id : validIds(cited, evidence)) {
            if (citations.size() >= MAX_CITATIONS) {
                break;
            }
            citations.add(citation(evidence.apply(id).orElseThrow()));
        }
        if (citations.isEmpty()) {
            throw new OutputException(FailureCode.NO_VALID_CITATIONS);
        }
        return new RunEventData.Report(answer, citations);
    }

    /**
     * Model or seller text made safe to show: {@link UntrustedText#clean} (one line, no control,
     * format or bidi characters, capped), markdown links reduced to their text, URLs and bare domains
     * replaced by {@value #LINK_REMOVED}, {@code <}/{@code >} neutralised.
     */
    static String scrub(String raw, int maxCodePoints) {
        String text = UntrustedText.clean(raw, maxCodePoints);
        text = MARKDOWN_LINK.matcher(text).replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
        text = LINK.matcher(text).replaceAll(LINK_REMOVED);
        text = text.replace('<', '‹').replace('>', '›');
        return UntrustedText.clean(text, maxCodePoints);
    }

    /** A citation rebuilt from evidence; a missing URL or title is derived from the validated chunk id. */
    static RunEventData.Citation citation(EvidenceCitation evidence) {
        Matcher matcher = CHUNK_ID.matcher(evidence.chunkId());
        String disclosure = matcher.matches() ? matcher.group(1) : "";
        String url = evidence.sourceUrl() != null ? evidence.sourceUrl() : KAP_PAGE + disclosure;
        String title = evidence.title() != null ? evidence.title() : "KAP disclosure " + disclosure;
        return new RunEventData.Citation(evidence.chunkId(), url, title);
    }

    private static List<String> validIds(List<String> ids, Function<String, Optional<EvidenceCitation>> evidence) {
        Set<String> kept = new LinkedHashSet<>();
        for (String id : ids.subList(0, Math.min(ids.size(), MAX_CITED_IDS))) {
            String trimmed = id.strip();
            if (CHUNK_ID.matcher(trimmed).matches() && evidence.apply(trimmed).isPresent()) {
                kept.add(trimmed);
            }
        }
        return List.copyOf(kept);
    }

    /** One JSON object with exactly {@code expected} fields, optionally inside a markdown fence. */
    private JsonNode object(@Nullable String text, Set<String> expected) {
        if (text == null || text.length() > MAX_OUTPUT_CHARS) {
            throw new OutputException(FailureCode.INVALID_MODEL_OUTPUT);
        }
        String body = text.strip();
        Matcher fence = FENCE.matcher(body);
        if (fence.matches()) {
            body = fence.group(1);
        }
        JsonNode root;
        try {
            root = reader.readTree(body);
        } catch (JacksonException e) {
            throw new OutputException(FailureCode.INVALID_MODEL_OUTPUT);
        }
        if (root == null || !root.isObject() || !fields(root).equals(expected)) {
            throw new OutputException(FailureCode.INVALID_MODEL_OUTPUT);
        }
        return root;
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            names.add(field.getKey());
        }
        return names;
    }

    private static String string(@Nullable JsonNode node) {
        if (node == null || !node.isString()) {
            throw new OutputException(FailureCode.INVALID_MODEL_OUTPUT);
        }
        return node.asString();
    }

    private static List<String> strings(@Nullable JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new OutputException(FailureCode.INVALID_MODEL_OUTPUT);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            values.add(string(element));
        }
        return values;
    }
}
