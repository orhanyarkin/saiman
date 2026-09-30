package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import io.github.orhanyarkin.saiman.sellerapi.llm.Deadline;
import io.github.orhanyarkin.saiman.sellerapi.llm.LlmRunProperties;
import io.github.orhanyarkin.x402.server.RequiresPayment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The paid question endpoint, {@code POST /v1/disclosures/{ticker}/questions}. Exists only in RAG
 * mode: without a corpus and a model there is nothing to sell, so in fixture mode the path is a
 * plain 404 and is not even registered as a paid resource.
 *
 * <p>The model runs before settlement, so this endpoint asks the starter for a long enough
 * authorization window ({@link LlmRunProperties#MIN_AUTHORIZATION_WINDOW_SECONDS}) and answers
 * within a deadline; see {@code UnsettledRunGuard} for the limits on unpaid runs.
 *
 * <p>Every non-2xx outcome (400 bad ticker or question, 404 unknown ticker, 422 too few
 * citations, 502/503 model or retrieval trouble) is produced here or below, after the starter
 * verified the payment and before it would settle, so none of them is ever charged.
 */
@RestController
@RequestMapping("/v1/disclosures")
@ConditionalOnProperty(name = "seller.disclosures.source", havingValue = "rag")
class DisclosureQuestionController {

    private static final String TICKER_PATTERN = "^[A-Z0-9]{3,6}$";

    private final DisclosureAnswerService service;
    private final LlmRunProperties llm;

    DisclosureQuestionController(DisclosureAnswerService service, LlmRunProperties llm) {
        this.service = service;
        this.llm = llm;
    }

    @PostMapping("/{ticker}/questions")
    @RequiresPayment(
            price = "${seller.prices.disclosure-answer}",
            description = "Cited answer to a question about a BIST company's public KAP disclosures",
            minWindowSeconds = LlmRunProperties.MIN_AUTHORIZATION_WINDOW_SECONDS)
    DisclosureAnswerResponse ask(
            @PathVariable @Pattern(regexp = TICKER_PATTERN) String ticker,
            @RequestBody @Valid DisclosureQuestionRequest request) {
        return service.answer(ticker, request.question(), Deadline.after(llm.deadline()));
    }
}
