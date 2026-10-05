package io.github.orhanyarkin.saiman.ledger.api;

import io.github.orhanyarkin.saiman.ledger.query.LedgerQueries;
import io.github.orhanyarkin.saiman.ledger.query.RevenueReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Seller revenue from the SELLER book (ADR-0021): gross sales, credit notes, net revenue, customer credits. */
@RestController
@RequestMapping("/api/v1/ledger")
public class RevenueController {

    private final LedgerQueries queries;

    public RevenueController(LedgerQueries queries) {
        this.queries = queries;
    }

    /** One row per seller ({@code payTo}) and asset, largest gross sales first. */
    @GetMapping("/revenue")
    public RevenueReport revenue() {
        return queries.revenue();
    }
}
