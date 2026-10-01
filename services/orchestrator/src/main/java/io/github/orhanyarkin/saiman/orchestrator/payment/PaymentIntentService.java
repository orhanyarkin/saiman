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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
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
    /** The allowed shape of every template variable any {@link SellerEndpoint} uses. */
    private static final Map<String, Pattern> VARIABLE_SHAPES = Map.of("ticker", Pattern.compile("[A-Z0-9]{3,6}"));

    private static final String COLUMNS = "id, run_id, tool, args_hash, resource, status, amount_atomic, pay_to,"
            + " network, asset, reserved_day, tx_hash, deny_reason";
    private static final String AUTHORIZATION_COLUMNS = "id, run_id, status, resource, network, asset, pay_to,"
            + " amount_atomic, payer, auth_nonce, valid_before, reserved_day, tx_hash";

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
     * @param uriVariables exactly the endpoint's template variables, each of its allowed shape (a
     *     ticker is {@code ^[A-Z0-9]{3,6}$})
     * @throws IllegalArgumentException if the variables don't match the endpoint's template or a
     *     value is not of its allowed shape; nothing is inserted then
     */
    public PaymentIntentHandle create(
            UUID runId, String tool, String argsHash, SellerEndpoint endpoint, Map<String, String> uriVariables) {
        if (!new HashSet<>(endpoint.variables()).equals(uriVariables.keySet())) {
            throw new IllegalArgumentException("uri variables must be exactly the endpoint's template variables");
        }
        uriVariables.forEach(PaymentIntentService::requireAllowedValue);
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
     * (returns empty) unless the signed recipient and amount are exactly what was reserved. Runs in the
     * caller's transaction, which publishes {@code PaymentAuthorized} from the returned row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<IntentAuthorization> markSigned(
            String idempotencyKey, String payTo, long amountAtomic, String payer, String nonce, long validBefore) {
        return jdbc.sql("""
                        UPDATE payment_intent
                           SET status = 'SIGNED', payer = :payer, auth_nonce = :nonce, valid_before = :validBefore,
                               updated_at = now()
                         WHERE idempotency_key = :key AND status = 'RESERVED'
                           AND amount_atomic = :amount AND lower(pay_to) = lower(:payTo)
                        RETURNING\s""" + AUTHORIZATION_COLUMNS)
                .param("key", idempotencyKey)
                .param("payTo", payTo)
                .param("amount", amountAtomic)
                .param("payer", payer)
                .param("nonce", nonce)
                .param("validBefore", validBefore)
                .query(PaymentIntentService::mapAuthorization)
                .optional();
    }

    /** The authorization of a signed (SIGNED, SETTLED, HELD, ...) intent, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<IntentAuthorization> findAuthorization(UUID id) {
        return jdbc.sql("SELECT " + AUTHORIZATION_COLUMNS
                        + " FROM payment_intent WHERE id = :id AND auth_nonce IS NOT NULL")
                .param("id", id)
                .query(PaymentIntentService::mapAuthorization)
                .optional();
    }

    /** Locks a signed intent and returns its authorization ({@code SELECT ... FOR UPDATE}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<IntentAuthorization> lockAuthorization(UUID id) {
        return jdbc.sql("SELECT " + AUTHORIZATION_COLUMNS
                        + " FROM payment_intent WHERE id = :id AND auth_nonce IS NOT NULL FOR UPDATE")
                .param("id", id)
                .query(PaymentIntentService::mapAuthorization)
                .optional();
    }

    /**
     * The authorization of a signed intent, read outside any transaction (the resolver reads chain facts
     * between short transactions and must not hold a connection while it waits for the RPC).
     */
    public Optional<IntentAuthorization> readAuthorization(UUID id) {
        return jdbc.sql("SELECT " + AUTHORIZATION_COLUMNS
                        + " FROM payment_intent WHERE id = :id AND auth_nonce IS NOT NULL")
                .param("id", id)
                .query(PaymentIntentService::mapAuthorization)
                .optional();
    }

    /**
     * Picks the HELD intents whose authorization expired before {@code expiredBefore} (unix seconds: the safe
     * block's timestamp) and stamps {@code resolution_attempted_at}, in one statement. Never-attempted intents
     * come first, then the least recently attempted, then the earliest {@code validBefore}: intents that keep
     * failing cannot block the ones behind them. A HELD intent without a nonce is not returned (see {@link
     * #claimHeldWithoutAuthorization(int)}).
     */
    public List<UUID> claimHeldExpiredBefore(long expiredBefore, int limit) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT id FROM payment_intent
                             WHERE status = 'HELD' AND auth_nonce IS NOT NULL AND valid_before < :expiredBefore
                             ORDER BY resolution_attempted_at NULLS FIRST, valid_before, id LIMIT :limit
                        )
                        UPDATE payment_intent p SET resolution_attempted_at = now()
                          FROM due WHERE p.id = due.id
                        RETURNING p.id
                        """)
                .param("expiredBefore", expiredBefore)
                .param("limit", limit)
                .query((rs, row) -> rs.getObject("id", UUID.class))
                .list();
    }

    /**
     * Picks (and stamps) HELD intents that never recorded an authorization: the signature provably never left
     * the process, so the resolver releases them without a chain read.
     */
    public List<UUID> claimHeldWithoutAuthorization(int limit) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT id FROM payment_intent
                             WHERE status = 'HELD' AND auth_nonce IS NULL
                             ORDER BY resolution_attempted_at NULLS FIRST, updated_at, id LIMIT :limit
                        )
                        UPDATE payment_intent p SET resolution_attempted_at = now()
                          FROM due WHERE p.id = due.id
                        RETURNING p.id
                        """)
                .param("limit", limit)
                .query((rs, row) -> rs.getObject("id", UUID.class))
                .list();
    }

    /**
     * HELD -> RELEASED for an intent that never recorded an authorization ({@code resolved_by = LOCAL}).
     *
     * @return false if the intent is no longer HELD or has an authorization
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markHeldUnsignedReleased(UUID id) {
        return jdbc.sql("""
                        UPDATE payment_intent
                           SET status = 'RELEASED', resolved_by = 'LOCAL', resolved_at = now(), updated_at = now()
                         WHERE id = :id AND status = 'HELD' AND auth_nonce IS NULL
                        """).param("id", id).update() == 1;
    }

    /** {@code resolved_by} (FACILITATOR, CHAIN or LOCAL) of a resolved intent. */
    public Optional<String> resolvedBy(UUID id) {
        return jdbc.sql("SELECT resolved_by FROM payment_intent WHERE id = :id AND resolved_by IS NOT NULL")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    /** HELD intents with no recorded authorization: they stay counted until an operator looks at them. */
    public int countHeldWithoutAuthorization() {
        return jdbc.sql("SELECT count(*) FROM payment_intent WHERE status = 'HELD' AND auth_nonce IS NULL")
                .query(Integer.class)
                .single();
    }

    /**
     * Signed intents in one of {@code statuses} whose {@code kind} payment event was never published
     * (no {@code payment_event_log} row): the startup backfill's work list.
     */
    public List<UUID> findUnpublished(String kind, List<String> statuses) {
        return jdbc.sql("""
                        SELECT p.id FROM payment_intent p
                         WHERE p.auth_nonce IS NOT NULL AND p.status IN (:statuses)
                           AND NOT EXISTS (SELECT 1 FROM payment_event_log l
                                            WHERE l.payment_intent_id = p.id AND l.kind = :kind)
                         ORDER BY p.created_at, p.id
                        """)
                .param("statuses", statuses)
                .param("kind", kind)
                .query((rs, row) -> rs.getObject("id", UUID.class))
                .list();
    }

    /** SIGNED -> SETTLED by the facilitator's answer. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markSettled(UUID id, String txHash) {
        int updated = jdbc.sql("""
                        UPDATE payment_intent
                           SET status = 'SETTLED', tx_hash = :txHash, resolved_by = 'FACILITATOR',
                               resolved_at = now(), updated_at = now()
                         WHERE id = :id AND status = 'SIGNED'
                        """).param("id", id).param("txHash", txHash).update();
        requireOne(updated);
    }

    /** HELD -> SETTLED from a chain read ({@code authorizationState == true}); the tx hash may be unknown. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markHeldSettled(UUID id, @Nullable String txHash) {
        int updated = jdbc.sql("""
                        UPDATE payment_intent
                           SET status = 'SETTLED', tx_hash = :txHash, resolved_by = 'CHAIN', resolved_at = now(),
                               updated_at = now()
                         WHERE id = :id AND status = 'HELD'
                        """)
                .param("id", id)
                .param("txHash", txHash, Types.VARCHAR)
                .update();
        requireOne(updated);
    }

    /** HELD -> RELEASED from a chain read ({@code authorizationState == false} past {@code validBefore}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markHeldReleased(UUID id) {
        int updated = jdbc.sql("""
                        UPDATE payment_intent
                           SET status = 'RELEASED', resolved_by = 'CHAIN', resolved_at = now(), updated_at = now()
                         WHERE id = :id AND status = 'HELD'
                        """).param("id", id).update();
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

    /** Locks every RESERVED intent, in id order (startup crash recovery). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PaymentIntentView> lockAllReserved() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment_intent WHERE status = 'RESERVED' ORDER BY id FOR UPDATE")
                .query(PaymentIntentService::map)
                .list();
    }

    /**
     * Every SIGNED -> HELD (startup crash recovery): the signature may have left the process, so
     * the amount keeps counting until M4 reconciles it.
     *
     * @return how many intents were held
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int holdAllSigned() {
        return jdbc.sql("UPDATE payment_intent SET status = 'HELD', updated_at = now() WHERE status = 'SIGNED'")
                .update();
    }

    /** PENDING -> RELEASED: the call ended before any payment was asked for or reserved. */
    public boolean closeUnsent(UUID id) {
        return jdbc.sql("""
                        UPDATE payment_intent SET status = 'RELEASED', updated_at = now()
                         WHERE id = :id AND status = 'PENDING'
                        """).param("id", id).update() == 1;
    }

    /**
     * Closes every intent of an ended run that never reserved anything: PENDING and APPROVED ->
     * RELEASED, AWAITING_APPROVAL -> EXPIRED ({@code APPROVAL_EXPIRED}). Nothing the run prepared
     * can be sent any more, and an approval decided in the last moment leaves no open APPROVED intent
     * behind. Reserved, signed and held intents are not touched (they are money, handled elsewhere).
     *
     * @return how many intents were closed
     */
    public int closeUnsentForRun(UUID runId) {
        return jdbc.sql("""
                        UPDATE payment_intent
                           SET status = CASE WHEN status = 'AWAITING_APPROVAL' THEN 'EXPIRED' ELSE 'RELEASED' END,
                               deny_reason = CASE WHEN status = 'AWAITING_APPROVAL' THEN 'APPROVAL_EXPIRED'
                                                  ELSE deny_reason END,
                               updated_at = now()
                         WHERE run_id = :runId AND status IN ('PENDING', 'APPROVED', 'AWAITING_APPROVAL')
                        """).param("runId", runId).update();
    }

    private URI resolve(SellerEndpoint endpoint, Map<String, String> uriVariables) {
        URI base = seller.baseUrl();
        // create() has already restricted every value to its allowed shape; this is the second
        // layer. encode() before expansion (template-and-values mode) percent-encodes "/", "?" and
        // "#" in a value but NOT ".": a value of ".." would survive, and the seller would normalise
        // /v1/disclosures/../summary to /v1/summary. So the allowed shape, not encoding, is the control.
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

    /**
     * Allowed-shape check for one template variable. An unknown variable name is refused (fail
     * closed); dot segments, empty values and anything containing {@code /} are refused explicitly
     * before the pattern, so the rule still holds if a pattern is widened later.
     */
    private static void requireAllowedValue(String name, String value) {
        Pattern shape = VARIABLE_SHAPES.get(name);
        if (shape == null) {
            throw new IllegalArgumentException("uri variable has no allowed shape");
        }
        if (value.isEmpty()
                || value.equals(".")
                || value.equals("..")
                || value.indexOf('/') >= 0
                || !shape.matcher(value).matches()) {
            throw new IllegalArgumentException("uri variable value is not of the allowed shape");
        }
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

    private static IntentAuthorization mapAuthorization(ResultSet rs, int row) throws SQLException {
        return new IntentAuthorization(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                PaymentIntentStatus.valueOf(rs.getString("status")),
                rs.getString("resource"),
                rs.getString("network"),
                rs.getString("asset"),
                rs.getString("pay_to"),
                rs.getLong("amount_atomic"),
                rs.getString("payer"),
                rs.getString("auth_nonce"),
                rs.getLong("valid_before"),
                rs.getObject("reserved_day", LocalDate.class),
                rs.getString("tx_hash"));
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
