package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import io.github.orhanyarkin.saiman.sellerapi.llm.LlmRunProperties;
import io.github.orhanyarkin.saiman.sellerapi.llm.RequestDeadlines;
import io.github.orhanyarkin.x402.core.PaymentFlow;
import io.github.orhanyarkin.x402.server.RequiresPayment;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first x402-paid resource: {@code GET /v1/disclosures/{ticker}/summary}.
 *
 * <p>{@link RequiresPayment} makes {@code RequiresPaymentInterceptor} (from
 * {@code x402-spring-boot-starter}) reject any request without a valid, unused payment for {@code
 * seller.prices.disclosure-summary} atomic units before this method ever runs. In the {@code
 * upfront} flow (ADR-0021) it also settles the payment before this method runs, so a summary is
 * never generated unpaid. A malformed ticker (400, method validation runs after the interceptor) or
 * an unknown one ({@link TickerNotFoundException}, 404) is therefore paid and not served: the buyer
 * gets the error with {@code PAYMENT-RESPONSE}, and the seller issues a credit note for the full
 * amount ({@code CreditNoteRecorder}). Fixture mode uses the same flow.
 *
 * <p>The {@code @Pattern} constraint on {@code ticker} is validated by Spring MVC's built-in
 * handler-method validation (Spring Framework 6.1+): a constraint directly on an
 * {@code @RequestMapping} method parameter is checked automatically whenever a {@code Validator}
 * bean exists (from {@code spring-boot-starter-validation}), with no {@code @Validated} needed on
 * this class -- {@code @Validated} at the class level switches to a different, AOP-proxy-based
 * validation path instead of this one, so it is deliberately not used here.
 */
@RestController
@RequestMapping("/v1/disclosures")
class DisclosureSummaryController {

    private static final String TICKER_PATTERN = "^[A-Z0-9]{3,6}$";

    private final DisclosureSummaryService service;
    private final RequestDeadlines deadlines;

    DisclosureSummaryController(DisclosureSummaryService service, RequestDeadlines deadlines) {
        this.service = service;
        this.deadlines = deadlines;
    }

    @GetMapping("/{ticker}/summary")
    @RequiresPayment(
            price = "${seller.prices.disclosure-summary}",
            description = "BIST public disclosure summary",
            minWindowSeconds = LlmRunProperties.MIN_AUTHORIZATION_WINDOW_SECONDS,
            paymentFlow = PaymentFlow.UPFRONT)
    DisclosureSummaryResponse summary(
            @PathVariable @Pattern(regexp = TICKER_PATTERN) String ticker, HttpServletRequest http) {
        return service.summaryFor(ticker, deadlines.forRequest(http));
    }
}
