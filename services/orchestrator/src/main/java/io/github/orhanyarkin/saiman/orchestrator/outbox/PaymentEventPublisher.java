package io.github.orhanyarkin.saiman.orchestrator.outbox;

import io.github.orhanyarkin.saiman.orchestrator.payment.IntentAuthorization;
import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.payments.AuthorizationRef;
import io.github.orhanyarkin.saiman.shared.payments.Book;
import io.github.orhanyarkin.saiman.shared.payments.Finality;
import io.github.orhanyarkin.saiman.shared.payments.PaymentAuthorized;
import io.github.orhanyarkin.saiman.shared.payments.PaymentFailed;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import io.github.orhanyarkin.saiman.shared.payments.SettlementEvidence;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes the buyer's payment events ({@code payments.*.v1}, book BUYER) in the caller's transaction, the one
 * that changed the intent (ADR-0016): if it rolls back, nothing is published. Spring Modulith's registry
 * persists the publication in that transaction and {@link OutboxConfiguration} routes it to its topic, keyed by
 * the payment key.
 *
 * <p>At most once per (intent, kind): a {@code payment_event_log} row is inserted with the event, and a second
 * attempt (a retry, or the startup backfill racing a live commit) finds the row and publishes nothing. The event
 * id is derived from the intent id and kind, so even a publication resubmitted after a crash carries the same id
 * and the ledger's inbox drops the duplicate.
 */
@Component
public class PaymentEventPublisher {

    /** {@link EventMetadata#producer()} of every orchestrator event. */
    public static final String PRODUCER = "orchestrator";
    /** {@code PaymentFailed.reasonCode} when the chain says the authorization expired unused. */
    public static final String EXPIRED_UNUSED = "expired_unused";

    /** The payment events the orchestrator produces. */
    public enum Kind {
        AUTHORIZED,
        SETTLED,
        FAILED
    }

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher publisher;

    public PaymentEventPublisher(JdbcClient jdbc, ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    /** {@code payments.authorized.v1}: the intent became SIGNED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean authorized(IntentAuthorization intent) {
        return publishOnce(
                intent,
                Kind.AUTHORIZED,
                meta -> new PaymentAuthorized(
                        meta,
                        reference(intent),
                        Money.usdc(intent.amountAtomic()),
                        intent.payTo(),
                        intent.resource(),
                        intent.id(),
                        intent.runId()));
    }

    /**
     * {@code payments.settled.v1}: the authorization was used.
     *
     * @param txHash the facilitator's transaction, or the one found on chain; null only with {@link
     *     SettlementEvidence#CHAIN}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean settled(IntentAuthorization intent, @Nullable String txHash, SettlementEvidence evidence) {
        return publishOnce(
                intent,
                Kind.SETTLED,
                meta -> new PaymentSettled(
                        meta,
                        reference(intent),
                        Money.usdc(intent.amountAtomic()),
                        intent.payTo(),
                        intent.resource(),
                        Book.BUYER,
                        txHash,
                        evidence,
                        intent.id(),
                        intent.runId()));
    }

    /** {@code payments.failed.v1} FINAL: the chain says the authorization expired unused. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expiredUnused(IntentAuthorization intent) {
        return publishOnce(
                intent,
                Kind.FAILED,
                meta -> new PaymentFailed(
                        meta,
                        reference(intent),
                        Money.usdc(intent.amountAtomic()),
                        intent.payTo(),
                        intent.resource(),
                        Book.BUYER,
                        Finality.FINAL,
                        EXPIRED_UNUSED,
                        intent.id(),
                        intent.runId()));
    }

    /** The deterministic event id of one (intent, kind): the same across retries, resubmissions and backfill. */
    public static UUID eventId(UUID intentId, Kind kind) {
        return UUID.nameUUIDFromBytes(
                ("saiman:orchestrator:payments:" + intentId + ":" + kind).getBytes(StandardCharsets.UTF_8));
    }

    private boolean publishOnce(IntentAuthorization intent, Kind kind, Function<EventMetadata, Object> factory) {
        UUID eventId = eventId(intent.id(), kind);
        // Built before anything is written: an invalid record (e.g. a non-testnet network) throws and the
        // caller's transaction rolls back with it.
        Object event = factory.apply(new EventMetadata(
                eventId.toString(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                PRODUCER,
                intent.runId().toString()));
        int inserted = jdbc.sql("""
                        INSERT INTO payment_event_log (payment_intent_id, kind, event_id)
                        VALUES (:intentId, :kind, :eventId)
                        ON CONFLICT DO NOTHING
                        """)
                .param("intentId", intent.id())
                .param("kind", kind.name())
                .param("eventId", eventId)
                .update();
        if (inserted == 0) {
            return false;
        }
        publisher.publishEvent(event);
        return true;
    }

    private static AuthorizationRef reference(IntentAuthorization intent) {
        return new AuthorizationRef(
                intent.network(), intent.asset(), intent.payer(), intent.nonce(), intent.validBefore());
    }
}
