package io.github.orhanyarkin.saiman.ledger.api;

import io.github.orhanyarkin.saiman.ledger.query.BookFilter;
import io.github.orhanyarkin.saiman.ledger.query.LedgerQueries;
import io.github.orhanyarkin.saiman.ledger.query.PaymentDetail;
import io.github.orhanyarkin.saiman.ledger.query.PaymentPage;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard's view of payments (M5, ADR-0022): a keyset-paged list and a drill-down, addressed by payment id.
 * Read-only, behind {@link LedgerApiGuardFilter}. Malformed parameters (a non-UUID id, an unknown book, a non-integer
 * limit) are 400 Problem Details from Spring MVC's type conversion.
 */
@RestController
@RequestMapping("/api/v1/ledger/payments")
public class LedgerPaymentsController {

    private final LedgerQueries queries;

    public LedgerPaymentsController(LedgerQueries queries) {
        this.queries = queries;
    }

    /**
     * Payments newest first.
     *
     * @param runId only payments of this agent run
     * @param book only payments with entries in this book (BUYER or SELLER)
     * @param limit page size, 1 to 100
     * @param before the previous page's {@code nextCursor}
     */
    @GetMapping
    @ApiResponse(responseCode = "200", description = "A page of payments", useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "400",
            description = "Malformed runId, book, limit or before",
            content = @Content(mediaType = ApiDocs.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    public PaymentPage payments(
            @RequestParam(required = false) @Nullable UUID runId,
            @RequestParam(required = false) @Nullable BookFilter book,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) @Nullable String before) {
        if (limit < 1 || limit > LedgerQueries.MAX_LIMIT) {
            throw ApiProblems.badRequest("limit must be between 1 and " + LedgerQueries.MAX_LIMIT);
        }
        try {
            return queries.payments(runId, book, limit, before);
        } catch (LedgerQueries.InvalidCursorException e) {
            throw ApiProblems.badRequest("before is not a cursor issued by this service");
        }
    }

    /** One payment with its journal entries, postings and reconciliation findings. */
    @GetMapping("/{paymentId}")
    @ApiResponse(responseCode = "200", description = "The payment", useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "400",
            description = "paymentId is not a UUID",
            content = @Content(mediaType = ApiDocs.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such payment",
            content = @Content(mediaType = ApiDocs.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    public PaymentDetail payment(@PathVariable UUID paymentId) {
        return queries.payment(paymentId).orElseThrow(() -> ApiProblems.notFound("No such payment"));
    }
}
