package io.github.orhanyarkin.saiman.ledger.api;

import io.github.orhanyarkin.saiman.ledger.journal.JournalRepository;
import io.github.orhanyarkin.saiman.ledger.journal.TrialBalanceRow;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read side of the books: the trial balance, a bare JSON array (what {@code make ledger-balance} prints). */
@RestController
@RequestMapping("/api/v1/ledger")
public class TrialBalanceController {

    private final JournalRepository journal;
    private final BoundedReads reads;

    TrialBalanceController(JournalRepository journal, BoundedReads reads) {
        this.journal = journal;
        this.reads = reads;
    }

    /** Every account and asset with its debit and credit totals and {@code balance = debit - credit}. */
    @GetMapping("/trial-balance")
    @ApiResponse(responseCode = "200", description = "OK", useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "503",
            description = "The read timed out or too many reads are running; retry after Retry-After seconds",
            headers =
                    @Header(
                            name = HttpHeaders.RETRY_AFTER,
                            description = "Seconds to wait",
                            schema = @Schema(type = "integer")),
            content = @Content(mediaType = ApiDocs.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    public List<TrialBalanceRow> trialBalance() {
        return reads.read(journal::trialBalance);
    }
}
