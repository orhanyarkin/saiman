package io.github.orhanyarkin.saiman.ledger.reconciliation;

import io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** SQL of the reconciliation: runs, items, mismatches, per-payment nets, internal checks and the report reads. */
@Repository
public class ReconciliationRepository {

    /** Signed amount of a posting: {@code +} debit, {@code -} credit. */
    private static final String SIGNED = "CASE p.side WHEN 'DEBIT' THEN p.amount_atomic ELSE -p.amount_atomic END";

    /** How much later an uncorroborated payment (no buyer fact) queues than its last check or creation. */
    static final String UNCORROBORATED_DELAY = "1 hour";

    /** How much later a payment whose reported tx was found missing or unrelated queues. */
    static final String SUSPECT_DELAY = "6 hours";

    private static final String WITH_TX = "p.buyer_tx_hash IS NOT NULL OR p.seller_tx_hash IS NOT NULL";

    private static final String WITHOUT_TX_DUE =
            "p.buyer_tx_hash IS NULL AND p.seller_tx_hash IS NULL AND p.valid_before < :safeTs - :grace";

    /**
     * Due order: corroborated payments by last check (never checked first); uncorroborated and suspect ones as if
     * checked (or, if never checked, created) a fixed delay later. Ties by creation and key, so it is total.
     */
    static final String DUE_ORDER = """
            CASE
                WHEN EXISTS (SELECT 1 FROM reconciliation_mismatch m
                              WHERE m.payment_id = p.id AND m.kind IN ('TX_NOT_FOUND', 'TX_NOT_FOR_AUTHORIZATION'))
                    THEN coalesce(p.last_checked_at, p.created_at) + interval '%s'
                WHEN p.buyer_state = 'NONE'
                    THEN coalesce(p.last_checked_at, p.created_at) + interval '%s'
                ELSE coalesce(p.last_checked_at, '-infinity')
            END, p.created_at, p.payment_key""".formatted(SUSPECT_DELAY, UNCORROBORATED_DELAY);

    private final JdbcClient jdbc;

    public ReconciliationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Counters of one run. */
    public record Counters(
            int checked, int matched, int pending, int resolvedUsed, int resolvedUnused, int mismatches) {}

    /** A run row. */
    public record Run(
            UUID id,
            Instant startedAt,
            @Nullable Instant finishedAt,
            String status,
            String network,
            @Nullable Long safeBlock,
            Counters counters) {}

    /** An item row joined with its payment. */
    public record ItemRow(
            UUID paymentId,
            @Nullable UUID paymentIntentId,
            @Nullable UUID runId,
            String payer,
            String payTo,
            Money amount,
            String buyerState,
            String sellerState,
            String chainState,
            @Nullable String txHash,
            String status,
            @Nullable String mismatchKind,
            @Nullable Long ledgerAtomic,
            @Nullable Long chainAtomic,
            @Nullable UUID adjustmentEntryId) {}

    /** Any run still RUNNING was interrupted (the caller holds the single-runner lock): mark it FAILED. */
    @Transactional
    public void failInterruptedRuns(Instant now) {
        jdbc.sql("UPDATE reconciliation_run SET status = 'FAILED', finished_at = :now WHERE status = 'RUNNING'")
                .param("now", Timestamp.from(now))
                .update();
    }

    @Transactional
    public void insertRun(UUID id, Instant startedAt, String network) {
        jdbc.sql("""
                        INSERT INTO reconciliation_run (id, started_at, status, network)
                        VALUES (:id, :startedAt, 'RUNNING', :network)
                        """)
                .param("id", id)
                .param("startedAt", Timestamp.from(startedAt))
                .param("network", network)
                .update();
    }

    @Transactional
    public void finishRun(UUID id, String status, Instant finishedAt, @Nullable Long safeBlock, Counters c) {
        jdbc.sql("""
                        UPDATE reconciliation_run
                           SET status = :status, finished_at = :finishedAt, safe_block = :safeBlock,
                               checked = :checked, matched = :matched, pending = :pending,
                               resolved_used = :resolvedUsed, resolved_unused = :resolvedUnused,
                               mismatches = :mismatches
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status)
                .param("finishedAt", Timestamp.from(finishedAt))
                .param("safeBlock", safeBlock)
                .param("checked", c.checked())
                .param("matched", c.matched())
                .param("pending", c.pending())
                .param("resolvedUsed", c.resolvedUsed())
                .param("resolvedUnused", c.resolvedUnused())
                .param("mismatches", c.mismatches())
                .update();
    }

    /**
     * Payments due for a check, in two shares so a flood of forged rows cannot starve real ones (M4 audit):
     *
     * <ul>
     *   <li><b>Reported tx</b> (buyer or seller reported a hash): at least half of the batch is reserved for them.
     *   <li><b>No reported tx</b>, due once {@code validBefore < safe - grace}: the rest of the batch.
     * </ul>
     *
     * An unused share goes to the other one. Within each share the order is {@link #DUE_ORDER}: least recently
     * checked first, but a payment no buyer fact corroborates (only the unauthenticated seller side reported it)
     * queues as if it had been checked {@link #UNCORROBORATED_DELAY} later, and one whose reported tx was already
     * found missing or unrelated ({@code TX_NOT_FOUND}, {@code TX_NOT_FOR_AUTHORIZATION}; those mismatch rows are
     * never deleted, so a payment stays suspect) {@link #SUSPECT_DELAY} later. Forged facts therefore queue behind
     * real payments yet still age into a batch: they are delayed, never starved. Matched payments stay due, so a
     * record corrupted after its first check is still caught (the tamper demo). The grace is subtracted from the
     * safe timestamp, never added to the column, so no stored value can overflow the comparison.
     */
    @Transactional(readOnly = true)
    public List<String> duePaymentKeys(long safeTimestamp, long graceSeconds, int limit) {
        List<String> withTx = jdbc.sql("""
                        SELECT payment_key FROM payment p
                         WHERE %s
                         ORDER BY %s
                         LIMIT :limit
                        """.formatted(WITH_TX, DUE_ORDER))
                .param("limit", limit)
                .query((rs, row) -> rs.getString(1))
                .list();
        List<String> withoutTx = jdbc.sql("""
                        SELECT payment_key FROM payment p
                         WHERE %s
                         ORDER BY %s
                         LIMIT :limit
                        """.formatted(WITHOUT_TX_DUE, DUE_ORDER))
                .param("safeTs", safeTimestamp)
                .param("grace", graceSeconds)
                .param("limit", limit)
                .query((rs, row) -> rs.getString(1))
                .list();
        return fairShare(withTx, withoutTx, limit);
    }

    /** The due backlog: how many payments are due now, and the oldest last check (or creation) among them. */
    public record Backlog(long due, @Nullable Instant oldestUnchecked) {}

    /** {@link Backlog} for the gauges, with the same due condition as {@link #duePaymentKeys}. */
    @Transactional(readOnly = true)
    public Backlog backlog(long safeTimestamp, long graceSeconds) {
        return jdbc.sql("""
                        SELECT count(*) AS due, min(coalesce(last_checked_at, created_at)) AS oldest
                          FROM payment p
                         WHERE (%s) OR (%s)
                        """.formatted(WITH_TX, WITHOUT_TX_DUE))
                .param("safeTs", safeTimestamp)
                .param("grace", graceSeconds)
                .query((rs, row) -> {
                    Timestamp oldest = rs.getTimestamp("oldest");
                    return new Backlog(rs.getLong("due"), oldest == null ? null : oldest.toInstant());
                })
                .single();
    }

    /** At least {@code ceil(limit / 2)} of {@code reserved} first, then {@code rest}, then whatever fits. */
    static List<String> fairShare(List<String> reserved, List<String> rest, int limit) {
        int share = Math.min(reserved.size(), (limit + 1) / 2);
        List<String> due = new ArrayList<>(reserved.subList(0, share));
        for (String key : rest) {
            if (due.size() >= limit) {
                break;
            }
            due.add(key);
        }
        for (int i = share; i < reserved.size() && due.size() < limit; i++) {
            due.add(reserved.get(i));
        }
        return List.copyOf(due);
    }

    /** Entries whose postings do not balance per asset (the deferred trigger forbids them; tampering does not). */
    @Transactional(readOnly = true)
    public long unbalancedEntries() {
        return jdbc.sql("""
                        SELECT count(*) FROM (
                            SELECT p.entry_id FROM posting p GROUP BY p.entry_id, p.asset, p.decimals
                            HAVING sum(%s) <> 0) AS unbalanced
                        """.formatted(SIGNED)).query(Long.class).single();
    }

    /** The ledger's net movements on this payment's wallets, over all of its entries (adjustments included). */
    @Transactional(propagation = Propagation.MANDATORY)
    public ChainReconciler.LedgerNets nets(PaymentProjection payment) {
        return jdbc.sql("""
                        SELECT coalesce(sum(%1$s) FILTER (WHERE p.account_code IN (:available, :encumbered)), 0) AS buyer,
                               coalesce(sum(%1$s) FILTER (WHERE p.account_code = :encumbered), 0) AS encumbered,
                               coalesce(sum(%1$s) FILTER (WHERE p.account_code = :seller), 0) AS seller
                          FROM posting p
                          JOIN journal_entry e ON e.id = p.entry_id
                         WHERE e.payment_id = :paymentId AND p.asset = :asset
                        """.formatted(SIGNED))
                .param(
                        "available",
                        ChartOfAccounts.buyerAvailable(payment.payer()).code())
                .param(
                        "encumbered",
                        ChartOfAccounts.buyerEncumbered(payment.payer()).code())
                .param("seller", ChartOfAccounts.sellerWallet(payment.payTo()).code())
                .param("paymentId", payment.id())
                .param("asset", payment.amount().asset())
                .query((rs, row) -> new ChainReconciler.LedgerNets(
                        exact(rs, "buyer"), exact(rs, "encumbered"), exact(rs, "seller")))
                .single();
    }

    /** Writes what reconciliation learned to the locked projection row. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateChain(PaymentProjection next, @Nullable Long chainBlock) {
        jdbc.sql("""
                        UPDATE payment
                           SET chain_state = :chainState, chain_tx_hash = :chainTx,
                               chain_block = coalesce(:chainBlock, chain_block),
                               last_checked_at = :checkedAt, updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", next.id())
                .param("chainState", next.chainState().name())
                .param("chainTx", next.chainTxHash())
                .param("chainBlock", chainBlock)
                .param("checkedAt", next.lastCheckedAt() == null ? null : Timestamp.from(next.lastCheckedAt()))
                .update();
    }

    /** Marks a payment checked without changing anything else (the chain could not answer). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void touch(UUID paymentId, Instant checkedAt) {
        jdbc.sql("UPDATE payment SET last_checked_at = :checkedAt WHERE id = :id")
                .param("id", paymentId)
                .param("checkedAt", Timestamp.from(checkedAt))
                .update();
    }

    /** Records a mismatch once per payment and kind; true if this call inserted it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertMismatch(
            UUID id,
            UUID runId,
            PaymentProjection payment,
            ChainReconciler.Finding finding,
            @Nullable UUID adjustmentEntryId) {
        return jdbc.sql("""
                                INSERT INTO reconciliation_mismatch (id, run_id, payment_id, kind,
                                       ledger_amount_atomic, chain_amount_atomic, asset, decimals,
                                       reported_tx_hash, chain_tx_hash, adjustment_entry_id)
                                VALUES (:id, :runId, :paymentId, :kind, :ledger, :chain, :asset, :decimals,
                                        :reportedTx, :chainTx, :adjustment)
                                ON CONFLICT (payment_id, kind) DO NOTHING
                                """)
                        .param("id", id)
                        .param("runId", runId)
                        .param("paymentId", payment.id())
                        .param("kind", finding.kind().name())
                        .param("ledger", atomic(finding.ledgerValue()))
                        .param("chain", atomic(finding.chainValue()))
                        .param("asset", payment.amount().asset())
                        .param("decimals", payment.amount().decimals())
                        .param("reportedTx", finding.reportedTxHash())
                        .param("chainTx", finding.chainTxHash())
                        .param("adjustment", adjustmentEntryId)
                        .update()
                == 1;
    }

    /** One item of a run (a payment is checked at most once per run). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertItem(
            UUID runId,
            UUID paymentId,
            ItemStatus status,
            @Nullable String txHash,
            ChainReconciler.@Nullable Finding finding,
            @Nullable UUID adjustmentEntryId) {
        jdbc.sql("""
                        INSERT INTO reconciliation_item (run_id, payment_id, status, tx_hash, mismatch_kind,
                                                         ledger_amount_atomic, chain_amount_atomic, adjustment_entry_id)
                        VALUES (:runId, :paymentId, :status, :txHash, :kind, :ledger, :chain, :adjustment)
                        ON CONFLICT (run_id, payment_id) DO NOTHING
                        """)
                .param("runId", runId)
                .param("paymentId", paymentId)
                .param("status", status.name())
                .param("txHash", txHash)
                .param("kind", finding == null ? null : finding.kind().name())
                .param("ledger", finding == null ? null : atomic(finding.ledgerValue()))
                .param("chain", finding == null ? null : atomic(finding.chainValue()))
                .param("adjustment", adjustmentEntryId)
                .update();
    }

    /**
     * {@code debit - credit} of {@code platform:suspense:usdc}, summed as {@code numeric} and clamped to
     * {@code ±Long.MAX_VALUE}: a flood of forged mismatches must not turn the report into a 500.
     */
    @Transactional(readOnly = true)
    public long suspenseBalance(Money of) {
        return jdbc.sql("SELECT coalesce(sum(%s), 0) FROM posting p WHERE p.account_code = :code AND p.asset = :asset"
                        .formatted(SIGNED))
                .param("code", ChartOfAccounts.suspense(of).code())
                .param("asset", of.asset())
                .query((rs, row) -> saturated(rs.getBigDecimal(1)))
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<Run> run(UUID id) {
        return jdbc.sql("SELECT * FROM reconciliation_run WHERE id = :id")
                .param("id", id)
                .query(ReconciliationRepository::mapRun)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<Run> latestRun() {
        return jdbc.sql("SELECT * FROM reconciliation_run ORDER BY started_at DESC, id LIMIT 1")
                .query(ReconciliationRepository::mapRun)
                .optional();
    }

    @Transactional(readOnly = true)
    public List<ItemRow> items(UUID runId) {
        return jdbc.sql("""
                        SELECT i.payment_id, p.payment_intent_id, p.run_id, p.payer, p.pay_to, p.amount_atomic,
                               p.asset, p.decimals, p.buyer_state, p.seller_state, p.chain_state, i.tx_hash,
                               i.status, i.mismatch_kind, i.ledger_amount_atomic, i.chain_amount_atomic,
                               i.adjustment_entry_id
                          FROM reconciliation_item i
                          JOIN payment p ON p.id = i.payment_id
                         WHERE i.run_id = :runId
                         ORDER BY i.checked_at, i.payment_id
                        """)
                .param("runId", runId)
                .query((rs, row) -> new ItemRow(
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("payment_intent_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getString("payer"),
                        rs.getString("pay_to"),
                        new Money(rs.getLong("amount_atomic"), rs.getString("asset"), rs.getInt("decimals")),
                        rs.getString("buyer_state"),
                        rs.getString("seller_state"),
                        rs.getString("chain_state"),
                        rs.getString("tx_hash"),
                        rs.getString("status"),
                        rs.getString("mismatch_kind"),
                        nullableLong(rs, "ledger_amount_atomic"),
                        nullableLong(rs, "chain_amount_atomic"),
                        rs.getObject("adjustment_entry_id", UUID.class)))
                .list();
    }

    private static Run mapRun(ResultSet rs, int row) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new Run(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(),
                rs.getString("status"),
                rs.getString("network"),
                nullableLong(rs, "safe_block"),
                new Counters(
                        rs.getInt("checked"),
                        rs.getInt("matched"),
                        rs.getInt("pending"),
                        rs.getInt("resolved_used"),
                        rs.getInt("resolved_unused"),
                        rs.getInt("mismatches")));
    }

    private static @Nullable Long atomic(@Nullable Money money) {
        return money == null ? null : money.atomicUnits();
    }

    private static @Nullable Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static long exact(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? 0 : value.longValueExact();
    }

    private static long saturated(@Nullable BigDecimal value) {
        if (value == null) {
            return 0;
        }
        BigDecimal max = BigDecimal.valueOf(Long.MAX_VALUE);
        return value.max(max.negate()).min(max).longValueExact();
    }
}
