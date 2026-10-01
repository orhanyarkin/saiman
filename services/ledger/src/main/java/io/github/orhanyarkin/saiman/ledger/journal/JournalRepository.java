package io.github.orhanyarkin.saiman.ledger.journal;

import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends journal entries and reads the trial balance. Insert-only: the database rejects updates and deletes of
 * entries and postings, and checks at commit that every entry balances (V1__ledger.sql).
 */
@Repository
public class JournalRepository {

    private final JdbcClient jdbc;

    public JournalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Posts one entry (creating its accounts on first use) inside the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void post(JournalEntry entry) {
        // Sorted, so concurrent transactions creating the same new accounts take their locks in one order.
        entry.postings().stream()
                .map(Posting::account)
                .distinct()
                .sorted(Comparator.comparing(Account::code))
                .forEach(this::ensureAccount);
        jdbc.sql("""
                        INSERT INTO journal_entry (id, payment_id, payment_key, book, kind, source_event_id,
                                                   reverses_entry_id, description, effective_at)
                        VALUES (:id, :paymentId, :paymentKey, :book, :kind, :sourceEventId,
                                :reversesEntryId, :description, :effectiveAt)
                        """)
                .param("id", entry.id())
                .param("paymentId", entry.paymentId())
                .param("paymentKey", entry.paymentKey())
                .param("book", entry.book().name())
                .param("kind", entry.kind().name())
                .param("sourceEventId", entry.sourceEventId())
                .param("reversesEntryId", entry.reversesEntryId())
                .param("description", entry.description())
                .param("effectiveAt", Timestamp.from(entry.effectiveAt()))
                .update();
        for (Posting posting : entry.postings()) {
            jdbc.sql("""
                            INSERT INTO posting (entry_id, account_code, side, amount_atomic, asset, decimals)
                            VALUES (:entryId, :account, :side, :amount, :asset, :decimals)
                            """)
                    .param("entryId", entry.id())
                    .param("account", posting.account().code())
                    .param("side", posting.side().name())
                    .param("amount", posting.amount().atomicUnits())
                    .param("asset", posting.amount().asset())
                    .param("decimals", posting.amount().decimals())
                    .update();
        }
    }

    /** One row per account and asset; {@code balance = debit - credit}, so all balances of an asset sum to zero. */
    @Transactional(readOnly = true)
    public List<TrialBalanceRow> trialBalance() {
        return jdbc.sql("""
                        SELECT a.code, a.book, a.type, a.asset, a.decimals,
                               coalesce(sum(p.amount_atomic) FILTER (WHERE p.side = 'DEBIT'), 0)  AS debit,
                               coalesce(sum(p.amount_atomic) FILTER (WHERE p.side = 'CREDIT'), 0) AS credit
                          FROM account a
                          LEFT JOIN posting p ON p.account_code = a.code AND p.asset = a.asset
                         GROUP BY a.code, a.book, a.type, a.asset, a.decimals
                         ORDER BY a.book, a.code, a.asset
                        """)
                .query((rs, row) -> {
                    long debit = rs.getBigDecimal("debit").longValueExact();
                    long credit = rs.getBigDecimal("credit").longValueExact();
                    return new TrialBalanceRow(
                            rs.getString("code"),
                            rs.getString("book"),
                            rs.getString("type"),
                            rs.getString("asset"),
                            rs.getInt("decimals"),
                            debit,
                            credit,
                            Math.subtractExact(debit, credit));
                })
                .list();
    }

    private void ensureAccount(Account account) {
        jdbc.sql("""
                        INSERT INTO account (code, book, type, asset, decimals, wallet)
                        VALUES (:code, :book, :type, :asset, :decimals, :wallet)
                        ON CONFLICT DO NOTHING
                        """)
                .param("code", account.code())
                .param("book", account.book().name())
                .param("type", account.type().name())
                .param("asset", account.asset())
                .param("decimals", account.decimals())
                .param("wallet", account.wallet())
                .update();
    }
}
