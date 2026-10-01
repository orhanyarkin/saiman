package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The {@code payment} projection: the one mutable ledger table, always changed under a row lock. */
@Repository
public class PaymentRepository {

    private static final String COLUMNS = """
            id, payment_key, network, asset_address, payer, nonce, pay_to, amount_atomic, asset, decimals,
            valid_before, payment_intent_id, run_id, buyer_state, seller_state, chain_state,
            buyer_tx_hash, seller_tx_hash, chain_tx_hash, last_checked_at
            """;

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the row unless the payment key exists, then locks and returns the current row. Two consumers racing
     * on a new payment both get past the insert (the loser waits for the winner's commit, then does nothing), and
     * both then see exactly one row; the lock serialises them for the rest of the transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentProjection insertIfAbsentAndLock(PaymentProjection initial) {
        jdbc.sql("""
                        INSERT INTO payment (id, payment_key, network, asset_address, payer, nonce, pay_to,
                                             amount_atomic, asset, decimals, valid_before, payment_intent_id, run_id,
                                             buyer_state, seller_state, chain_state)
                        VALUES (:id, :key, :network, :assetAddress, :payer, :nonce, :payTo,
                                :amount, :asset, :decimals, :validBefore, :intentId, :runId,
                                :buyerState, :sellerState, :chainState)
                        ON CONFLICT (payment_key) DO NOTHING
                        """)
                .param("id", initial.id())
                .param("key", initial.paymentKey())
                .param("network", initial.network())
                .param("assetAddress", initial.assetAddress())
                .param("payer", initial.payer())
                .param("nonce", initial.nonce())
                .param("payTo", initial.payTo())
                .param("amount", initial.amount().atomicUnits())
                .param("asset", initial.amount().asset())
                .param("decimals", initial.amount().decimals())
                .param("validBefore", initial.validBefore())
                .param("intentId", initial.paymentIntentId())
                .param("runId", initial.runId())
                .param("buyerState", initial.buyerState().name())
                .param("sellerState", initial.sellerState().name())
                .param("chainState", initial.chainState().name())
                .update();
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE payment_key = :key FOR UPDATE")
                .param("key", initial.paymentKey())
                .query(PaymentRepository::map)
                .single();
    }

    /** Writes the mutable columns of a row locked by {@link #insertIfAbsentAndLock}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(PaymentProjection p) {
        jdbc.sql("""
                        UPDATE payment
                           SET payment_intent_id = :intentId, run_id = :runId,
                               buyer_state = :buyerState, seller_state = :sellerState, chain_state = :chainState,
                               buyer_tx_hash = :buyerTx, seller_tx_hash = :sellerTx, chain_tx_hash = :chainTx,
                               last_checked_at = :lastCheckedAt, updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", p.id())
                .param("intentId", p.paymentIntentId())
                .param("runId", p.runId())
                .param("buyerState", p.buyerState().name())
                .param("sellerState", p.sellerState().name())
                .param("chainState", p.chainState().name())
                .param("buyerTx", p.buyerTxHash())
                .param("sellerTx", p.sellerTxHash())
                .param("chainTx", p.chainTxHash())
                .param("lastCheckedAt", p.lastCheckedAt() == null ? null : Timestamp.from(p.lastCheckedAt()))
                .update();
    }

    @Transactional(readOnly = true)
    public Optional<PaymentProjection> findByKey(String paymentKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE payment_key = :key")
                .param("key", paymentKey)
                .query(PaymentRepository::map)
                .optional();
    }

    private static PaymentProjection map(ResultSet rs, int row) throws SQLException {
        Timestamp lastChecked = rs.getTimestamp("last_checked_at");
        return new PaymentProjection(
                rs.getObject("id", UUID.class),
                rs.getString("payment_key"),
                rs.getString("network"),
                rs.getString("asset_address"),
                rs.getString("payer"),
                rs.getString("nonce"),
                rs.getString("pay_to"),
                new Money(rs.getLong("amount_atomic"), rs.getString("asset"), rs.getInt("decimals")),
                rs.getLong("valid_before"),
                uuid(rs, "payment_intent_id"),
                uuid(rs, "run_id"),
                BuyerState.valueOf(rs.getString("buyer_state")),
                SellerState.valueOf(rs.getString("seller_state")),
                ChainState.valueOf(rs.getString("chain_state")),
                rs.getString("buyer_tx_hash"),
                rs.getString("seller_tx_hash"),
                rs.getString("chain_tx_hash"),
                lastChecked == null ? null : lastChecked.toInstant());
    }

    private static @Nullable UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }
}
