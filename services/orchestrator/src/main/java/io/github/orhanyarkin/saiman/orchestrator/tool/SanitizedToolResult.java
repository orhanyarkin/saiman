package io.github.orhanyarkin.saiman.orchestrator.tool;

import java.util.List;

/**
 * What survives of a seller answer: its text and allowlisted citation fields.
 *
 * @param text the answer or summary, cleaned for a model
 */
public record SanitizedToolResult(String text, List<EvidenceCitation> citations) {

    public SanitizedToolResult {
        citations = List.copyOf(citations);
    }
}
