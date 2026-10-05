package io.github.orhanyarkin.saiman.evals.golden;

/** What an item measures. RETRIEVAL and FRESHNESS are scored in Tier R; ANSWER and UNANSWERABLE in Tier A. */
public enum Kind {
    /** Which disclosures come back for a topical question. */
    RETRIEVAL,
    /** Whether "latest ..." questions return the newest disclosures. */
    FRESHNESS,
    /** An answerable question with expected sources and required facts. */
    ANSWER,
    /** A question the corpus cannot answer; the correct behaviour is a refusal. */
    UNANSWERABLE
}
