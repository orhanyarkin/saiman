package io.github.orhanyarkin.saiman.orchestrator.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a seller response (untrusted) into the only thing a model ever sees of it (ADR-0014): an
 * allowlist, not a blocklist.
 *
 * <ul>
 *   <li>the {@code answer} (or {@code summary}) text, cleaned by {@link UntrustedText#forModel} and
 *       capped at {@value #MAX_TEXT} code points;
 *   <li>per citation (at most {@value #MAX_CITATIONS}, deduplicated): {@code chunkId} matching
 *       {@code kap:\d{1,10}:\d{4}} (else the citation is dropped), {@code sourceUrl} only if it is
 *       exactly a KAP disclosure page (else the field is dropped), and a cleaned {@code title} capped
 *       at {@value #MAX_TITLE} code points;
 *   <li>every other field is dropped.
 * </ul>
 *
 * The result is rendered as JSON inside a {@code <tool_data>} block; no {@code <} or {@code >} is
 * left inside, so the block can't be closed or a new one opened from within.
 */
@Component
public class ToolResultSanitizer {

    static final int MAX_TEXT = 2_000;
    static final int MAX_TITLE = 200;
    static final int MAX_CITATIONS = 10;
    static final String OPEN = "<tool_data>";
    static final String CLOSE = "</tool_data>";

    private static final Pattern CHUNK_ID = Pattern.compile("kap:\\d{1,10}:\\d{4}");
    private static final Pattern KAP_URL = Pattern.compile("https://www\\.kap\\.org\\.tr/tr/Bildirim/\\d{1,10}");

    private final JsonMapper json;

    public ToolResultSanitizer(JsonMapper json) {
        this.json = json;
    }

    /** Sanitises one response body; empty if it is not a JSON object with a non-empty answer or summary. */
    public Optional<SanitizedToolResult> sanitize(String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String raw = text(root, "answer");
        if (raw == null) {
            raw = text(root, "summary");
        }
        if (raw == null) {
            return Optional.empty();
        }
        String text = UntrustedText.forModel(raw, MAX_TEXT);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SanitizedToolResult(text, citations(root.get("citations"))));
    }

    /** The model-facing form: {@code <tool_data>{"answer": ..., "citations": [...]}</tool_data>}. */
    public String render(SanitizedToolResult result) {
        ObjectNode node = json.createObjectNode();
        node.put("answer", result.text());
        ArrayNode citations = node.putArray("citations");
        for (EvidenceCitation citation : result.citations()) {
            ObjectNode c = citations.addObject();
            c.put("chunkId", citation.chunkId());
            if (citation.sourceUrl() != null) {
                c.put("sourceUrl", citation.sourceUrl());
            }
            if (citation.title() != null) {
                c.put("title", citation.title());
            }
        }
        return OPEN + json.writeValueAsString(node) + CLOSE;
    }

    private static List<EvidenceCitation> citations(@Nullable JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Map<String, EvidenceCitation> byChunk = new LinkedHashMap<>();
        for (JsonNode element : node) {
            if (byChunk.size() >= MAX_CITATIONS) {
                break;
            }
            if (!element.isObject()) {
                continue;
            }
            String chunkId = text(element, "chunkId");
            if (chunkId == null || !CHUNK_ID.matcher(chunkId).matches()) {
                continue;
            }
            String url = text(element, "sourceUrl");
            String sourceUrl = url != null && KAP_URL.matcher(url).matches() ? url : null;
            String rawTitle = text(element, "title");
            String title = rawTitle == null ? null : UntrustedText.forModel(rawTitle, MAX_TITLE);
            byChunk.putIfAbsent(
                    chunkId, new EvidenceCitation(chunkId, sourceUrl, title == null || title.isEmpty() ? null : title));
        }
        return new ArrayList<>(byChunk.values());
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }
}
