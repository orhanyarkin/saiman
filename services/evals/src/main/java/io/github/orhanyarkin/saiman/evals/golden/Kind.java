package io.github.orhanyarkin.saiman.evals.golden;

/** What an item measures. RETRIEVAL and FRESHNESS are scored in Tier R; ANSWER, UNANSWERABLE and TEMPORAL in Tier A. */
public enum Kind {
    /** Which disclosures come back for a topical question. */
    RETRIEVAL,
    /** Whether "latest ..." questions return the newest disclosures. */
    FRESHNESS,
    /** An answerable question with expected sources and required facts. */
    ANSWER,
    /** A question the corpus cannot answer; the correct behaviour is a refusal. */
    UNANSWERABLE,
    /**
     * A recency-phrased question ("güncel bildirimler", "en son açıklamalar"). The corpus is a frozen snapshot, so a
     * correct answer uses absolute dates only; scored by the absence of relative-time expressions (Tier A).
     */
    TEMPORAL
}
