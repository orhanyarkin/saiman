package io.github.orhanyarkin.saiman.ledger.api;

import io.github.orhanyarkin.saiman.ledger.query.LedgerQueries;
import io.github.orhanyarkin.saiman.ledger.query.RevenueReport;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller revenue from the SELLER book (ADR-0021): gross sales, credit notes, net revenue and customer credits per
 * books, next to the chain-verified part (see {@link RevenueReport}).
 */
@RestController
@RequestMapping("/api/v1/ledger")
public class RevenueController {

    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");

    private final LedgerQueries queries;
    private final BoundedReads reads;

    RevenueController(LedgerQueries queries, BoundedReads reads) {
        this.queries = queries;
        this.reads = reads;
    }

    /**
     * One row per seller ({@code payTo}) and asset, largest gross sales first, at most 100 rows ({@code truncated}
     * says there are more; ask for one seller with {@code payTo}).
     *
     * @param payTo only this seller's rows (an EVM address, any case)
     */
    @GetMapping("/revenue")
    @ApiResponse(responseCode = "200", description = "Revenue per seller", useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "400",
            description = "payTo is not an address",
            content = @Content(mediaType = ApiDocs.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "503",
            description = "The read timed out or too many reads are running; retry after Retry-After seconds",
            headers =
                    @Header(
                            name = HttpHeaders.RETRY_AFTER,
                            description = "Seconds to wait",
                            schema = @Schema(type = "integer")),
            content = @Content(mediaType = ApiDocs.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    public RevenueReport revenue(@RequestParam(required = false) @Nullable String payTo) {
        if (payTo != null && !ADDRESS.matcher(payTo).matches()) {
            throw ApiProblems.badRequest("payTo is invalid");
        }
        String seller = payTo == null ? null : payTo.toLowerCase(Locale.ROOT);
        return reads.read(() -> queries.revenue(seller));
    }
}
