package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body of {@code POST /v1/disclosures/{ticker}/questions}.
 *
 * @param question the buyer's question, 3-500 characters; untrusted input
 */
public record DisclosureQuestionRequest(
        @NotBlank @Size(min = 3, max = 500) String question) {}
