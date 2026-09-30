package io.github.orhanyarkin.saiman.orchestrator.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The chunks one run actually retrieved through its tools (first occurrence wins, bounded). The
 * synthesis step keeps only citations whose chunk id is in here and rebuilds them from here
 * (ADR-0014), so a model cannot invent a source.
 */
public final class RunEvidence {

    static final int MAX_ENTRIES = 200;

    private final Map<String, EvidenceCitation> byChunkId = new LinkedHashMap<>();

    synchronized void addAll(List<EvidenceCitation> citations) {
        for (EvidenceCitation citation : citations) {
            if (byChunkId.size() >= MAX_ENTRIES) {
                return;
            }
            byChunkId.putIfAbsent(citation.chunkId(), citation);
        }
    }

    public synchronized Optional<EvidenceCitation> find(String chunkId) {
        return Optional.ofNullable(byChunkId.get(chunkId));
    }

    /** Every retrieved chunk, in retrieval order. */
    public synchronized List<EvidenceCitation> all() {
        return List.copyOf(byChunkId.values());
    }

    public synchronized boolean isEmpty() {
        return byChunkId.isEmpty();
    }
}
