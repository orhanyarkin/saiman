package io.github.orhanyarkin.saiman.orchestrator.payment;

import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Creates payment intents and owns every write to {@code payment_intent}.
 *
 * <p>The methods marked {@link Propagation#MANDATORY} lock or change a row as one step of a larger
 * money decision (the spend guard's or the approval service's transaction) and refuse to run
 * outside one. The others are single conditional statements, atomic on their own.
 */
@Service
public class PaymentIntentService {

    private static final int IDEMPOTENCY_KEY_BYTES = 16; // 128 bits
    private static final String COLUMNS = "id, run_id, tool, args_hash, resource, status, amount_atomic, pay_to,"
            + " network, asset, reserved_day, tx_hash, deny_reason";

    private final JdbcClient jdbc;
    private final SellerProperties seller;
    private final SecureRandom random = new SecureRandom();

    public PaymentIntentService(JdbcClient jdbc, SellerProperties seller) {
        this.jdbc = jdbc;
        this.seller = seller;
    }

    /**
     * Inserts a PENDING intent for one paid call, with a fresh 128-bit random idempotency key
     * (never derived from model text or arguments).
     *
     * @param tool the research tool name (for dedupe and the run's audit trail)
     * @param argsHash a hash of the validated, code-rendered arguments
     * @param uriVariables exactly the endpoint's template variables; values are strictly encoded
     * @throws IllegalArgumentException if the variables don't match the endpoint's template
     */
    public PaymentIntentHandle create(
            UUID runId, String tool, String argsHash, SellerEndpoint endpoint, Map<String, String> uriVariables) {
        if (!new HashSet<>(endpoint.variables()).equals(uriVariables.keySet())) {
            throw new IllegalArgumentException("uri variables must be exactly the endpoint's template variables");
        }
        URI resource = resolve(endpoint, uriVariables);
        UUID id = UUID.randomUUID();
        String key = newIdempotencyKey();
        jdbc.sql("""
                        INSERT INTO payment_intent (id, run_id, idempotency_key, tool, args_hash, resource, status)
                        VALUES (:id, :runId, :key, :tool, :argsHash, :resource, 'PENDING')
                        """)
                .param("id", id)
                .param("runId", runId)
                .param("key", key)
                .param("tool", tool)
                .param("argsHash", argsHash)
                .param("resource", resource.toString())
                .update();
        return new PaymentIntentHandle(id, runId, endpoint, resource, key);
    }

    public Optional<PaymentIntentView> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment_intent WHERE id = :id")
                .param("id", id)
                .query(PaymentIntentService::map)
                .optional();
    }

    /** An earlier SETTLED intent of the same run for the same tool and arguments, for dedupe. */
    public Optional<PaymentIntentView> findSettled(UUID runId, String tool, String argsHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment_intent WHERE run_id = :runId AND tool = :tool"
                        + " AND args_hash = :argsHash AND status = 'SETTLED' ORDER BY created_at LIMIT 1")
                .param("runId", runId)
                .param("tool", tool)
                .param("argsHash", argsHash)
                .query(PaymentIntentService::map)
                .optional();
    }

    /** The approval requested for this intent, if any. */
    public Optional<UUID> findApprovalId(UUID intentId) {
        return jdbc.sql("SELECT id FROM approval WHERE payment_intent_id = :id")
                .param("id", intentId)
                .query(UUID.class)
                .optional();
    }

    /** Locks the intent with this idempotency key ({@code SELECT ... FOR UPDATE}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PaymentIntentView> lockByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment_intent WHERE idempotency_key = :key FOR UPDATE")
                .param("key", idempotencyKey)
                .query(PaymentIntentService::map)
                .optional();
    }

    /** Locks the intent ({@code SELECT ... FOR UPDATE}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PaymentIntentView> lockById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment_intent WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(PaymentIntentService::map)
                .optional();
    }

    /**
     * PENDING or APPROVED -> DENIED, recording the reason and, if known, the offer. One conditional
     * statement, so it is safe inside the guard's transaction and on its own.
     */
    public void markDenied(UUID id, DenyReason reason, @Nullable OfferedPayment offer) {
        int updated = jdbc.sql("""
                        UPDATE payment_intent
                           SET status = 'DENIED', deny_reason = :reason,
                               amount_atomic = COALESCE(:amount, amount_atomic), pay_to = COALESCE(:payTo, pay_to),
                               network = COALESCE(:network, network), asset = COALESCE(:asset, asset),
                               updated_at = now()
                         WHERE id = :id AND status IN ('PENDING', 'APPROVED')
                        """)
                .param("id", id)
                .param("reason", reason.name())
                .param("amount", offer == null ? null : offer.amountAtomic(), Types.BIGINT)
                .param("payTo", offer == null ? null : offer.payTo(), Types.VARCHAR)
                .param("network", offer == null ? null : offer.network(), Types.VARCHAR)
                .param("asset", offer == null ? null : offer.asset(), Types.VARCHAR)
                .update();
        requireOne(updated);
    }

    /** PENDING -> AWAITING_APPROVAL. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markAwaitingApproval(UUID id, OfferedPayment offer) {
        int updated = jdbc.sql("""
                        UPDATE payment_intent
                           SET status = 'AWAITING_APPROVAL', amount_atomic = :amount, pay_to = :payTo,
                               network = :network, asset = :asset, updated_at = now()
                         WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .param("amount", offer.amountAtomic())
                .param("payTo", offer.payTo())
                .param("network", offer.network())
                .param("asset", offer.asset())
                .update();
        requireOne(updated);
    }

    /** PENDING or APPROVED -> RESERVED on {@code day}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markReserved(UUID id, OfferedPayment offer, LocalDate day) {
        int updated = jdbc.sql("""
                        UPDATE payment_intent
                           SET status = 'RESERVED', amount_atomic = :amount, pay_to = :payTo, network = :network,
                               asset = :asset, reserved_day = :day, updated_at = now()
                         WHERE id = :id AND status IN ('PENDING', 'APPROVED')
                        """)
                .param("id", id)
                .param("amount", offer.amountAtomic())
                .param("payTo", offer.payTo())
                .param("network", offer.network())
                .param("asset", offer.asset())
                .param("day", day)
                .update();
        requireOne(updated);
    }

    /**
     * RESERVED -> SIGNED, recording what M4 needs to reconcile the authorization on chain. Refuses
     * (returns false) unless the signed recipient and amount are exactly what was reserved.
     */
    public boolean markSigned(
            String idempotencyKey, String payTo, long amountAtomic, String payer, String nonce, long validBefore) {
        return jdbc.sql("""
                        UPDATE payment_intent
                           SET status = 'SIGNED', payer = :payer, auth_nonce = :nonce, valid_before = :validBefore,
                               updated_at = now()
                         WHERE idempotency_key = :key AND status = 'RESERVED'
                           AND amount_atomic = :amount AND lower(pay_to) = lower(:payTo)
                        """)
                        .param("key", idempotencyKey)
                        .param("payTo", payTo)
                        .param("amount", amountAtomic)
                        .param("payer", payer)
                        .param("nonce", nonce)
                        .param("validBefore", validBefore)
                        .update()
                == 1;
    }

    /** SIGNED -> SETTLED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markSettled(UUID id, String txHash) {
        int updated = jdbc.sql("""
                        UPDATE payment_intent SET status = 'SETTLED', tx_hash = :txHash, updated_at = now()
                         WHERE id = :id AND status = 'SIGNED'
                        """).param("id", id).param("txHash", txHash).update();
        requireOne(updated);
    }

    /** RESERVED -> RELEASED (nothing was sent). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markReleased(UUID id) {
        int updated = jdbc.sql("""
                        UPDATE payment_intent SET status = 'RELEASED', updated_at = now()
                         WHERE id = :id AND status = 'RESERVED'
                        """).param("id", id).update();
        requireOne(updated);
    }

    /**
     * AWAITING_APPROVAL -> APPROVED, REJECTED or EXPIRED, as decided by the approval service.
     *
     * @return false if the intent was not awaiting approval
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markApprovalOutcome(UUID id, PaymentIntentStatus outcome, @Nullable DenyReason reason) {
        if (outcome != PaymentIntentStatus.APPROVED
                && outcome != PaymentIntentStatus.REJECTED
                && outcome != PaymentIntentStatus.EXPIRED) {
            throw new IllegalArgumentException("not an approval outcome");
        }
        return jdbc.sql("""
                        UPDATE payment_intent SET status = :status, deny_reason = :reason, updated_at = now()
                         WHERE id = :id AND status = 'AWAITING_APPROVAL'
                        """)
                        .param("id", id)
                        .param("status", outcome.name())
                        .param("reason", reason == null ? null : reason.name(), Types.VARCHAR)
                        .update()
                == 1;
    }

    /**
     * RESERVED or SIGNED -> HELD: a signature may have left the process and the outcome is unknown.
     * The amount stays in the reserved counters (a held reservation keeps counting) until M4
     * reconciles it on chain.
     */
    public boolean markHeld(UUID id) {
        return jdbc.sql("""
                        UPDATE payment_intent SET status = 'HELD', updated_at = now()
                         WHERE id = :id AND status IN ('RESERVED', 'SIGNED')
                        """).param("id", id).update() == 1;
    }

    /** PENDING -> RELEASED: the call ended before any payment was asked for or reserved. */
    public boolean closeUnsent(UUID id) {
        return jdbc.sql("""
                        UPDATE payment_intent SET status = 'RELEASED', updated_at = now()
                         WHERE id = :id AND status = 'PENDING'
                        """).param("id", id).update() == 1;
    }

    private URI resolve(SellerEndpoint endpoint, Map<String, String> uriVariables) {
        URI base = seller.baseUrl();
        // encode() before expansion: template-and-values mode, so variable values are fully
        // percent-encoded ("/", "?", "#" and ".." can't escape the path segment).
        URI resource = UriComponentsBuilder.newInstance()
                .scheme(base.getScheme())
                .host(base.getHost())
                .port(base.getPort())
                .path(endpoint.pathTemplate())
                .encode()
                .buildAndExpand(uriVariables)
                .toUri();
        if (!Objects.equals(resource.getHost(), base.getHost()) || resource.getPort() != base.getPort()) {
            throw new IllegalStateException("resolved resource left the configured seller");
        }
        return resource;
    }

    private String newIdempotencyKey() {
        byte[] bytes = new byte[IDEMPOTENCY_KEY_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void requireOne(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("payment intent is not in the expected state");
        }
    }

    private static PaymentIntentView map(ResultSet rs, int row) throws SQLException {
        long amount = rs.getLong("amount_atomic");
        Long amountAtomic = rs.wasNull() ? null : amount;
        String denyReason = rs.getString("deny_reason");
        return new PaymentIntentView(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("tool"),
                rs.getString("args_hash"),
                rs.getString("resource"),
                PaymentIntentStatus.valueOf(rs.getString("status")),
                amountAtomic,
                rs.getString("pay_to"),
                rs.getString("network"),
                rs.getString("asset"),
                rs.getObject("reserved_day", LocalDate.class),
                rs.getString("tx_hash"),
                denyReason == null ? null : DenyReason.valueOf(denyReason));
    }
}
