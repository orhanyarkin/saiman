package io.github.orhanyarkin.saiman.ingest.pipeline;

/** What happened to one disclosure in one run. */
public enum Outcome {
    /** Fetched, chunked, embedded and finalized. */
    INDEXED,
    /** Chunks already in place for the same content hash: finalized without an embedding call. */
    REPAIRED,
    /** Already terminal (or parked); no detail call, no embedding. */
    SKIPPED,
    /** A cancellation notice: recorded, the cancelled disclosure superseded, nothing chunked. */
    CANCELLATION,
    /** Purged or never indexed because KAP blocked it. */
    BLOCKED,
    /** Failed {@code max-attempts} times and parked in the DLQ. */
    FAILED
}
