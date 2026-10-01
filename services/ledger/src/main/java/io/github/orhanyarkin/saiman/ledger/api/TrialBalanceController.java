package io.github.orhanyarkin.saiman.ledger.api;

import io.github.orhanyarkin.saiman.ledger.journal.JournalRepository;
import io.github.orhanyarkin.saiman.ledger.journal.TrialBalanceRow;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read side of the books: the trial balance, a bare JSON array (what {@code make ledger-balance} prints). */
@RestController
@RequestMapping("/api/v1/ledger")
public class TrialBalanceController {

    private final JournalRepository journal;

    public TrialBalanceController(JournalRepository journal) {
        this.journal = journal;
    }

    /** Every account and asset with its debit and credit totals and {@code balance = debit - credit}. */
    @GetMapping("/trial-balance")
    public List<TrialBalanceRow> trialBalance() {
        return journal.trialBalance();
    }
}
