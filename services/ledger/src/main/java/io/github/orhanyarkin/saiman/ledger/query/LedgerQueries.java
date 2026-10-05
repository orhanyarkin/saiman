package io.github.orhanyarkin.saiman.ledger.query;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only SQL behind the dashboard. Selects exactly the columns it exposes: {@code payment_key} and {@code nonce}
 * are never read here, so they cannot leak into a response by accident.
 */
@Repository
public class LedgerQueries {

    /** Largest page the payments list returns. */
    public static final int MAX_LIMIT = 100;

    /** Most sellers the revenue report lists. */
    public static final int MAX_REVENUE_ROWS = 100;

    /** 2^53-1: the largest integer every JSON client reads exactly. */
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private static final String SUMMARY_COLUMNS = """
            p.id, p.run_id, p.buyer_state, p.seller_state, p.chain_state, p.amount_atomic, p.asset, p.decimals,
            p.pay_to, p.created_at, p.updated_at, p.buyer_tx_hash, p.seller_tx_hash, p.chain_tx_hash""";

    private final JdbcClient jdbc;
    private final Clock clock;

    public LedgerQueries(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A {@code before} token that this service did not issue. */
    public static final class InvalidCursorException extends RuntimeException {
        InvalidCursorException() {
            super("invalid cursor");
        }
    }

    /**
     * Payments newest first, keyset-paged on {@code (created_at, id)}.
     *
     * @param before the previous page's {@code nextCursor}
     * @throws InvalidCursorException if {@code before} is not a cursor this service issued, or its instant lies
     *     outside [2025-01-01, now + 1 day]
     */
    @Transactional(readOnly = true)
    public PaymentPage payments(@Nullable UUID runId, @Nullable BookFilter book, int limit, @Nullable String before) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (runId != null) {
            where.add("p.run_id = :runId");
            params.put("runId", runId);
        }
        if (book != null) {
            where.add("EXISTS (SELECT 1 FROM journal_entry e WHERE e.payment_id = p.id AND e.book = :book)");
            params.put("book", book.name());
        }
        if (before != null) {
            PaymentCursor cursor =
                    PaymentCursor.decode(before, clock.instant()).orElseThrow(InvalidCursorException::new);
            where.add("(p.created_at, p.id) < (:beforeAt, :beforeId)");
            params.put("beforeAt", OffsetDateTime.ofInstant(cursor.createdAt(), ZoneOffset.UTC));
            params.put("beforeId", cursor.paymentId());
        }
        params.put("fetch", limit + 1);
        String sql = "SELECT " + SUMMARY_COLUMNS + " FROM payment p"
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY p.created_at DESC, p.id DESC LIMIT :fetch";
        List<PaymentSummary> rows =
                jdbc.sql(sql).params(params).query(LedgerQueries::summary).list();
        if (rows.size() <= limit) {
            return new PaymentPage(rows, null);
        }
        List<PaymentSummary> page = rows.subList(0, limit);
        PaymentSummary last = page.getLast();
        return new PaymentPage(page, new PaymentCursor(last.createdAt(), last.paymentId()).encode());
    }

    /** One payment with its entries, postings and reconciliation findings; empty for an unknown id. */
    @Transactional(readOnly = true)
    public Optional<PaymentDetail> payment(UUID paymentId) {
        Optional<PaymentSummary> summary = jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM payment p WHERE p.id = :id")
                .param("id", paymentId)
                .query(LedgerQueries::summary)
                .optional();
        return summary.map(s -> new PaymentDetail(s, entries(paymentId), findings(paymentId)));
    }

    private List<PaymentDetail.Entry> entries(UUID paymentId) {
        record Row(
                UUID entryId,
                String book,
                String kind,
                String description,
                Instant effectiveAt,
                @Nullable UUID reversesEntryId,
                PaymentDetail.Line line) {}
        List<Row> rows = jdbc.sql("""
                        SELECT e.id, e.book, e.kind, e.description, e.effective_at, e.reverses_entry_id,
                               p.account_code, p.side, p.amount_atomic, p.asset, p.decimals
                          FROM journal_entry e
                          JOIN posting p ON p.entry_id = e.id
                         WHERE e.payment_id = :id
                         ORDER BY e.recorded_at, e.id, p.id
                        """)
                .param("id", paymentId)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("book"),
                        rs.getString("kind"),
                        rs.getString("description"),
                        instant(rs, "effective_at"),
                        rs.getObject("reverses_entry_id", UUID.class),
                        new PaymentDetail.Line(
                                rs.getString("account_code"),
                                rs.getString("side"),
                                new Money(rs.getLong("amount_atomic"), rs.getString("asset"), rs.getInt("decimals")))))
                .list();
        Map<UUID, List<Row>> byEntry = new LinkedHashMap<>();
        rows.forEach(r ->
                byEntry.computeIfAbsent(r.entryId(), id -> new ArrayList<>()).add(r));
        return byEntry.values().stream()
                .map(lines -> {
                    Row first = lines.getFirst();
                    return new PaymentDetail.Entry(
                            first.entryId(),
                            first.book(),
                            first.kind(),
                            first.description(),
                            first.effectiveAt(),
                            first.reversesEntryId(),
                            lines.stream().map(Row::line).toList());
                })
                .toList();
    }

    private List<PaymentDetail.Finding> findings(UUID paymentId) {
        return jdbc.sql("""
                        SELECT m.kind, m.run_id, m.detected_at, m.adjustment_entry_id, m.ledger_amount_atomic,
                               m.chain_amount_atomic, coalesce(m.asset, p.asset) AS asset,
                               coalesce(m.decimals, p.decimals) AS decimals
                          FROM reconciliation_mismatch m
                          JOIN payment p ON p.id = m.payment_id
                         WHERE m.payment_id = :id
                         ORDER BY m.detected_at, m.id
                        """)
                .param("id", paymentId)
                .query((rs, n) -> {
                    UUID adjustment = rs.getObject("adjustment_entry_id", UUID.class);
                    return new PaymentDetail.Finding(
                            rs.getString("kind"),
                            adjustment == null ? "REPORTED" : "ADJUSTED",
                            rs.getObject("run_id", UUID.class),
                            instant(rs, "detected_at"),
                            adjustment,
                            money(rs, "ledger_amount_atomic"),
                            money(rs, "chain_amount_atomic"));
                })
                .list();
    }

    /**
     * Seller revenue per {@code payTo} and asset from the SELLER book's accounts (ADR-0021), each a signed net summed as
     * {@code numeric}: {@code revenue:data} credits minus debits, {@code revenue:credit-notes} debits minus credits,
     * {@code liability:customer-credits} credits minus debits, so REVERSAL entries count. The chain-verified split
     * follows {@link RevenueReport.Verified}: a sale needs {@code chain_state = 'USED'} <em>and</em> a
     * {@code chain_tx_hash} (reconciliation matched a canonical receipt; a used authorization whose transaction was not
     * found, TX_UNKNOWN, is not enough) and no blocking finding. {@code openFindings} counts the seller's payments (with
     * SELLER-book entries) that have a {@code reconciliation_mismatch} row without an adjustment entry (unresolved). At
     * most {@link #MAX_REVENUE_ROWS} rows, largest gross first.
     *
     * @param payTo only this seller (lower-case), or null for all
     */
    @Transactional(readOnly = true)
    public RevenueReport revenue(@Nullable String payTo) {
        List<RevenueReport.Seller> rows = jdbc.sql("""
                        WITH lines AS (
                            SELECT a.wallet AS pay_to, a.asset, a.decimals,
                                   substr(a.code, length('seller:' || a.wallet || ':') + 1) AS acct,
                                   CASE p.side WHEN 'CREDIT' THEN p.amount_atomic::numeric
                                               ELSE -p.amount_atomic::numeric END AS signed,
                                   coalesce(pm.chain_state = 'USED' AND pm.chain_tx_hash IS NOT NULL AND NOT EXISTS (
                                       SELECT 1 FROM reconciliation_mismatch m
                                        WHERE m.payment_id = pm.id
                                          AND m.kind NOT IN ('ENCUMBRANCE_NOT_CLEARED', 'BOOKS_OPEN',
                                                             'CREDIT_NOTE_UNCORROBORATED')), false) AS sale_verified,
                                   EXISTS (
                                       SELECT 1 FROM credit_note_corroboration c
                                        WHERE c.payment_id = pm.id AND c.tx_hash = pm.seller_tx_hash
                                          AND c.amount_atomic = pm.amount_atomic) AS note_corroborated
                              FROM account a
                              JOIN posting p ON p.account_code = a.code AND p.asset = a.asset
                              JOIN journal_entry e ON e.id = p.entry_id
                              LEFT JOIN payment pm ON pm.id = e.payment_id
                             WHERE a.book = 'SELLER' AND a.wallet IS NOT NULL
                               AND (CAST(:payTo AS text) IS NULL OR a.wallet = CAST(:payTo AS text))
                               AND a.code IN ('seller:' || a.wallet || ':revenue:data',
                                              'seller:' || a.wallet || ':revenue:credit-notes',
                                              'seller:' || a.wallet || ':liability:customer-credits')),
                        totals AS (
                            SELECT pay_to, asset, decimals,
                                   coalesce(sum(signed) FILTER (WHERE acct = 'revenue:data'), 0) AS gross,
                                   coalesce(-sum(signed) FILTER (WHERE acct = 'revenue:credit-notes'), 0)
                                       AS credit_notes,
                                   coalesce(sum(signed) FILTER (WHERE acct = 'liability:customer-credits'), 0)
                                       AS customer_credits,
                                   coalesce(sum(signed) FILTER (WHERE acct = 'revenue:data' AND sale_verified), 0)
                                       AS v_gross,
                                   coalesce(-sum(signed) FILTER (
                                       WHERE acct = 'revenue:credit-notes' AND sale_verified AND note_corroborated), 0)
                                       AS v_credit_notes
                              FROM lines
                             GROUP BY pay_to, asset, decimals),
                        counts AS (
                            SELECT pm.pay_to, pm.asset,
                                   count(*) FILTER (WHERE e.kind = 'SALE')        AS sales,
                                   count(*) FILTER (WHERE e.kind = 'CREDIT_NOTE') AS credited
                              FROM journal_entry e
                              JOIN payment pm ON pm.id = e.payment_id
                             WHERE e.book = 'SELLER' AND e.kind IN ('SALE', 'CREDIT_NOTE')
                               AND (CAST(:payTo AS text) IS NULL OR pm.pay_to = CAST(:payTo AS text))
                             GROUP BY pm.pay_to, pm.asset),
                        findings AS (
                            SELECT pm.pay_to, pm.asset, count(DISTINCT m.payment_id) AS open_findings
                              FROM reconciliation_mismatch m
                              JOIN payment pm ON pm.id = m.payment_id
                             WHERE m.adjustment_entry_id IS NULL
                               AND (CAST(:payTo AS text) IS NULL OR pm.pay_to = CAST(:payTo AS text))
                               AND EXISTS (SELECT 1 FROM journal_entry e
                                            WHERE e.payment_id = pm.id AND e.book = 'SELLER')
                             GROUP BY pm.pay_to, pm.asset)
                        SELECT t.pay_to, t.asset, t.decimals, t.gross, t.credit_notes,
                               t.gross - t.credit_notes AS net, t.customer_credits,
                               t.v_gross, t.v_credit_notes, t.v_gross - t.v_credit_notes AS v_net,
                               greatest(t.gross - t.v_gross, 0) AS unverified,
                               coalesce(c.sales, 0) AS sales, coalesce(c.credited, 0) AS credited,
                               coalesce(f.open_findings, 0) AS open_findings
                          FROM totals t
                          LEFT JOIN counts c ON c.pay_to = t.pay_to AND c.asset = t.asset
                          LEFT JOIN findings f ON f.pay_to = t.pay_to AND f.asset = t.asset
                         ORDER BY t.gross DESC, t.pay_to, t.asset
                         LIMIT :fetch
                        """)
                .param("payTo", payTo)
                .param("fetch", MAX_REVENUE_ROWS + 1)
                .query((rs, n) -> {
                    String asset = rs.getString("asset");
                    int decimals = rs.getInt("decimals");
                    Clamp clamp = new Clamp();
                    Money gross = new Money(clamp.amount(rs.getBigDecimal("gross")), asset, decimals);
                    Money creditNotes = new Money(clamp.amount(rs.getBigDecimal("credit_notes")), asset, decimals);
                    SignedAmount net = new SignedAmount(clamp.signed(rs.getBigDecimal("net")), asset, decimals);
                    Money customerCredits =
                            new Money(clamp.amount(rs.getBigDecimal("customer_credits")), asset, decimals);
                    RevenueReport.Verified verified = new RevenueReport.Verified(
                            new Money(clamp.amount(rs.getBigDecimal("v_gross")), asset, decimals),
                            new Money(clamp.amount(rs.getBigDecimal("v_credit_notes")), asset, decimals),
                            new SignedAmount(clamp.signed(rs.getBigDecimal("v_net")), asset, decimals));
                    Money unverified = new Money(clamp.amount(rs.getBigDecimal("unverified")), asset, decimals);
                    return new RevenueReport.Seller(
                            rs.getString("pay_to"),
                            gross,
                            creditNotes,
                            net,
                            customerCredits,
                            rs.getLong("sales"),
                            rs.getLong("credited"),
                            verified,
                            unverified,
                            rs.getLong("open_findings"),
                            clamp.happened);
                })
                .list();
        boolean truncated = rows.size() > MAX_REVENUE_ROWS;
        return new RevenueReport(truncated ? rows.subList(0, MAX_REVENUE_ROWS) : rows, truncated);
    }

    private static PaymentSummary summary(ResultSet rs, int row) throws SQLException {
        return new PaymentSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("buyer_state"),
                rs.getString("seller_state"),
                rs.getString("chain_state"),
                new Money(rs.getLong("amount_atomic"), rs.getString("asset"), rs.getInt("decimals")),
                rs.getString("pay_to"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getString("buyer_tx_hash"),
                rs.getString("seller_tx_hash"),
                rs.getString("chain_tx_hash"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    /** A mismatch amount as Money, if the row has one and its asset (both are optional columns). */
    private static @Nullable Money money(ResultSet rs, String column) throws SQLException {
        long atomic = rs.getLong(column);
        if (rs.wasNull()) {
            return null;
        }
        // Reconciliation stores absolute values; a negative one would be a broken row, not something to show.
        return atomic < 0 ? null : new Money(atomic, rs.getString("asset"), rs.getInt("decimals"));
    }

    /**
     * Clamps numeric sums to what every JSON client reads exactly (2^53-1, the bound V3 puts on single amounts) and
     * remembers whether it had to. A flood of forged events must not turn the report into a 500 or a wrong number.
     */
    private static final class Clamp {
        private static final BigDecimal MAX = BigDecimal.valueOf(MAX_SAFE_INTEGER);
        private boolean happened;

        /** For Money: [0, 2^53-1]. */
        long amount(BigDecimal value) {
            if (value.signum() < 0) {
                happened = true;
                return 0;
            }
            return signed(value);
        }

        /** For SignedAmount: [-(2^53-1), 2^53-1]. */
        long signed(BigDecimal value) {
            if (value.compareTo(MAX) > 0) {
                happened = true;
                return MAX_SAFE_INTEGER;
            }
            if (value.compareTo(MAX.negate()) < 0) {
                happened = true;
                return -MAX_SAFE_INTEGER;
            }
            return value.longValueExact();
        }
    }
}
