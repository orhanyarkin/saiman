package io.github.orhanyarkin.saiman.shared.eval;

import java.time.Instant;

/** A citation that survived the answer service's chunk-id check. */
public record EvalCitation(String chunkId, String sourceUrl, Instant publishedAt) {}
