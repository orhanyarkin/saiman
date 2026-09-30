package io.github.orhanyarkin.saiman.ingest.store;

/** Lifecycle of a {@code source_document} row. Only {@code INDEXED} documents are retrievable. */
public enum DocumentStatus {
    PENDING,
    INDEXED,
    FAILED,
    SUPERSEDED,
    BLOCKED,
    /** MKK answered "disclosure not found" (ER005/ER008) for the detail: nothing to index, never asked again. */
    MISSING;

    /** Nothing more to do for the document (and, for the pipeline, no detail call needed). */
    public boolean terminal() {
        return this == INDEXED || this == SUPERSEDED || this == BLOCKED || this == MISSING;
    }
}
