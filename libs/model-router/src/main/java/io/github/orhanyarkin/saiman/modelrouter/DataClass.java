package io.github.orhanyarkin.saiman.modelrouter;

/**
 * Data classification of what is sent to a model (ADR-0003). The router refuses a route whose
 * provider does not allow the class, before any network call.
 */
public enum DataClass {
    /** Public text such as KAP disclosures. */
    PUBLIC,
    /** Run metadata, budgets and buyer questions. */
    INTERNAL,
    /** Anything user-identifying; not collected in this project. */
    SENSITIVE
}
