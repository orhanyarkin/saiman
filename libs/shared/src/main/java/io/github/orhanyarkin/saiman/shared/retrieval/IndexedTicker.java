package io.github.orhanyarkin.saiman.shared.retrieval;

/** A ticker present in the corpus with its document and chunk counts ({@code GET /internal/v1/tickers}). */
public record IndexedTicker(String ticker, long documents, long chunks) {}
