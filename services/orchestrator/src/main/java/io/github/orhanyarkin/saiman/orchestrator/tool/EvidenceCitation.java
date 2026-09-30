package io.github.orhanyarkin.saiman.orchestrator.tool;

import org.jspecify.annotations.Nullable;

/**
 * One retrieved chunk as the sanitiser kept it.
 *
 * @param chunkId {@code kap:<disclosureIndex>:<chunkNumber>}, validated
 * @param sourceUrl a KAP disclosure page URL of the exact allowed form, or null if the seller's was
 *     anything else
 * @param title cleaned, capped title text, or null
 */
public record EvidenceCitation(
        String chunkId,
        @Nullable String sourceUrl,
        @Nullable String title) {}
