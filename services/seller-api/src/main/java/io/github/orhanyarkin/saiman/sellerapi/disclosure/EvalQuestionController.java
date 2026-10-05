package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import io.github.orhanyarkin.saiman.sellerapi.llm.ModelCostMeter;
import io.github.orhanyarkin.saiman.sellerapi.llm.RequestDeadlines;
import io.github.orhanyarkin.saiman.sellerapi.retrieval.RetrievalUnavailableException;
import io.github.orhanyarkin.saiman.sellerapi.settlement.InternalApiProperties;
import io.github.orhanyarkin.saiman.shared.eval.EvalAnswerRequest;
import io.github.orhanyarkin.saiman.shared.eval.EvalAnswerResponse;
import io.github.orhanyarkin.saiman.shared.eval.EvalCitation;
import io.github.orhanyarkin.saiman.shared.eval.EvalOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/v1/eval/questions}: the eval harness's Tier A (ADR-0025). Runs the <em>same</em> answer
 * service as the paid {@code POST /v1/disclosures/{ticker}/questions} (retrieval, prompt, parser, citation rebuild),
 * without x402: there is no {@code @RequiresPayment}, so nothing is verified, settled, recorded, credited or published.
 * It writes no row and emits no event.
 *
 * <p><b>Access.</b> Only the {@code evals} service token ({@code SERVICE_evals}, the security chain), only through
 * the compose network ({@code Host} must be one of {@code seller.internal.allowed-hosts}, checked here so it holds for
 * any path form); nginx never routes {@code /internal}. Limits: the run guard under caller {@code evals}
 * ({@code seller.eval.*}: 1 in flight, 60 per hour, 100 per UTC day by default; 429 when reached, 503 when the guard
 * can not decide) and the router's daily USD cap.
 *
 * <p><b>Shared day cap.</b> Eval calls are charged to the same router daily USD cap as paid traffic. Once eval calls
 * have used it up, a paid question is still settled up front and then answered 503 with a credit note for the full
 * amount (ADR-0021). {@code seller.eval.max-runs-per-day} bounds how much of the cap the harness can take.
 *
 * <p><b>Input bounds</b> are the paid endpoint's: ticker {@code ^[A-Z0-9]{3,6}$}, question 3 to 500 characters and
 * not blank. A bad request is a 400 with fixed text.
 *
 * <p><b>Outcomes</b> (200, {@link EvalAnswerResponse}; {@code modelCostUsdMicros} is the router's own cost
 * observation of this request's chat-model calls in seller-api, 0 when none ran; it excludes the query embedding ingest
 * computes for retrieval, which is charged in ingest):
 *
 * <ul>
 *   <li>{@code ANSWERED}: at least two valid citations; the answer and the cited chunks with their publication time.
 *   <li>{@code NO_VALID_CITATIONS}: the model answered but cited fewer than two retrieved excerpts (the paid endpoint
 *       answers 422 here). The answer text is returned for analysis, with the citations that were valid.
 *   <li>{@code REFUSED}: refused before any model call: the ticker is not indexed, or fewer than two usable excerpts
 *       were retrieved.
 *   <li>{@code LLM_CAP}: the router's daily USD cap is used up (nothing was sent).
 *   <li>{@code ERROR}: retrieval or the model was unavailable, the reply was malformed, or time ran out.
 * </ul>
 */
@RestController
@ConditionalOnProperty(name = "seller.disclosures.source", havingValue = "rag")
class EvalQuestionController {

    static final String PATH = "/internal/v1/eval/questions";

    private static final URI INSTANCE = URI.create(PATH);
    private static final Pattern TICKER = Pattern.compile("[A-Z0-9]{3,6}");
    private static final int MIN_QUESTION = 3;
    private static final int MAX_QUESTION = 500;

    private final DisclosureAnswerService service;
    private final RequestDeadlines deadlines;
    private final ModelCostMeter costs;
    private final List<String> allowedHosts;
    private final MeterRegistry meters;

    EvalQuestionController(
            DisclosureAnswerService service,
            RequestDeadlines deadlines,
            ModelCostMeter costs,
            InternalApiProperties internal,
            MeterRegistry meters) {
        this.service = service;
        this.deadlines = deadlines;
        this.costs = costs;
        this.allowedHosts = internal.allowedHosts();
        this.meters = meters;
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EvalAnswerResponse> ask(
            @RequestBody(required = false) @Nullable EvalAnswerRequest request,
            @RequestHeader(value = HttpHeaders.HOST, required = false) @Nullable String host,
            HttpServletRequest http) {
        if (host == null || !allowedHosts.contains(host.trim().toLowerCase(Locale.ROOT))) {
            throw refuse("host_refused", HttpStatus.BAD_REQUEST, "Host not allowed");
        }
        if (request == null || !valid(request.ticker(), request.question())) {
            throw refuse("bad_request", HttpStatus.BAD_REQUEST, "Malformed eval question");
        }
        EvalAnswerResponse response;
        try (ModelCostMeter.Measurement cost = costs.measure()) {
            EvalOutcome outcome;
            @Nullable String answer = null;
            List<EvalCitation> citations = List.of();
            try {
                DisclosureAnswerService.Grounded grounded =
                        service.groundForEval(request.ticker(), request.question(), deadlines.forRequest(http));
                outcome = grounded.sufficientlyCited() ? EvalOutcome.ANSWERED : EvalOutcome.NO_VALID_CITATIONS;
                answer = grounded.text();
                citations = grounded.cited().stream()
                        .map(chunk -> new EvalCitation(chunk.chunkId(), chunk.sourceUrl(), chunk.publishedAt()))
                        .toList();
            } catch (TickerNotFoundException e) {
                outcome = EvalOutcome.REFUSED;
            } catch (InsufficientCitationsException e) {
                outcome = e.modelRan() ? EvalOutcome.NO_VALID_CITATIONS : EvalOutcome.REFUSED;
            } catch (ModelUnavailableException e) {
                outcome = e.dailyCap() ? EvalOutcome.LLM_CAP : EvalOutcome.ERROR;
            } catch (RetrievalUnavailableException | MalformedModelOutputException | InsufficientTimeException e) {
                outcome = EvalOutcome.ERROR;
            }
            // RunLimitExceededException / RunGuardUnavailableException propagate: 429 / 503 (DisclosureProblemAdvice).
            response = new EvalAnswerResponse(outcome, answer, citations, cost.usdMicros());
        }
        meters.counter(
                        "saiman.seller.eval.answers",
                        "outcome",
                        response.outcome().name())
                .increment();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    private static boolean valid(@Nullable String ticker, @Nullable String question) {
        return ticker != null
                && TICKER.matcher(ticker).matches()
                && question != null
                && !question.isBlank()
                && question.length() >= MIN_QUESTION
                && question.length() <= MAX_QUESTION;
    }

    private ErrorResponseException refuse(String outcome, HttpStatus status, String detail) {
        meters.counter("saiman.seller.eval.answers", "outcome", outcome).increment();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(INSTANCE);
        return new ErrorResponseException(status, problem, null);
    }
}
