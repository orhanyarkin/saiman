package io.github.orhanyarkin.saiman.shared.retrieval;

import java.time.Instant;
import java.util.List;

/**
 * The result of a hybrid retrieval.
 *
 * @param chunks fused results, best first
 * @param corpusWatermark the newest publication time in the indexed corpus; for the frozen 2023 KAP
 *     snapshot (ADR-0010) this is a constant, and callers key caches on it
 */
public record RetrieveResponse(List<RetrievedChunk> chunks, Instant corpusWatermark) {

    public RetrieveResponse {
        chunks = List.copyOf(chunks);
    }
}
