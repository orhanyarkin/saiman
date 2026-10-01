package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import io.github.orhanyarkin.saiman.sellerapi.llm.LlmRunProperties;
import io.github.orhanyarkin.saiman.sellerapi.llm.RequestDeadlines;
import io.github.orhanyarkin.x402.core.PaymentFlow;
import io.github.orhanyarkin.x402.server.RequiresPayment;
import jakarta.servlet.http.HttpServletRequest;
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
 * <p>The x402 {@code upfront} flow (ADR-0021): the starter verifies <em>and settles</em> the payment
 * before this method runs, so no model run is ever unpaid. The endpoint still asks for a long
 * authorization window ({@link LlmRunProperties#MIN_AUTHORIZATION_WINDOW_SECONDS}); once settled,
 * the handler gets the full configured deadline ({@link RequestDeadlines}).
 *
 * <p>Every non-2xx outcome (400 bad ticker or question, 404 unknown ticker, 422 too few
 * citations, 429 per-payer limit, 502/503 model or retrieval trouble) is produced after the
 * settlement: the buyer gets that status with {@code PAYMENT-RESPONSE}, and the seller issues a
 * credit note for the full amount ({@code CreditNoteRecorder}).
 */
@RestController
@RequestMapping("/v1/disclosures")
@ConditionalOnProperty(name = "seller.disclosures.source", havingValue = "rag")
class DisclosureQuestionController {

    private static final String TICKER_PATTERN = "^[A-Z0-9]{3,6}$";

    private final DisclosureAnswerService service;
    private final RequestDeadlines deadlines;

    DisclosureQuestionController(DisclosureAnswerService service, RequestDeadlines deadlines) {
        this.service = service;
        this.deadlines = deadlines;
    }

    @PostMapping("/{ticker}/questions")
    @RequiresPayment(
            price = "${seller.prices.disclosure-answer}",
            description = "Cited answer to a question about a BIST company's public KAP disclosures",
            minWindowSeconds = LlmRunProperties.MIN_AUTHORIZATION_WINDOW_SECONDS,
            paymentFlow = PaymentFlow.UPFRONT)
    DisclosureAnswerResponse ask(
            @PathVariable @Pattern(regexp = TICKER_PATTERN) String ticker,
            @RequestBody @Valid DisclosureQuestionRequest request,
            HttpServletRequest http) {
        return service.answer(ticker, request.question(), deadlines.forRequest(http));
    }
}
