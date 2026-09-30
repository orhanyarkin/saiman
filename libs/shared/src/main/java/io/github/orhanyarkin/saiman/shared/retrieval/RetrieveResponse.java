package io.github.orhanyarkin.saiman.shared.retrieval;

import java.time.Instant;
import java.util.List;

/**
 * The result of a hybrid retrieval.
 *
 * @param chunks fused results, best first
 * @param corpusWatermark the newest publication time in the indexed corpus, shown to buyers as
 *     "corpus as of" (for the frozen 2023 KAP snapshot, ADR-0010, effectively a constant)
 * @param corpusVersion an opaque token that changes whenever the retrievable corpus changes,
 *     including when a disclosure is removed (blocked or superseded); callers key caches on it,
 *     never on the watermark, so a purged disclosure can not keep being served from a cache
 */
public record RetrieveResponse(List<RetrievedChunk> chunks, Instant corpusWatermark, String corpusVersion) {

    public RetrieveResponse {
        chunks = List.copyOf(chunks);
        if (corpusVersion.isBlank()) {
            throw new IllegalArgumentException("corpusVersion must not be blank");
        }
    }
}
