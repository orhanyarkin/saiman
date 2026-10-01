package io.github.orhanyarkin.saiman.sellerapi.settlement;

import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.payments.AuthorizationRef;
import io.github.orhanyarkin.saiman.shared.payments.Book;
import io.github.orhanyarkin.saiman.shared.payments.CreditNoteIssued;
import io.github.orhanyarkin.x402.server.X402PaidRequestFailedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issues a credit note for every upfront-flow request that was paid but not served (ADR-0021): the starter settled
 * the payment before the handler ran, then the handler answered 3xx/4xx/5xx or threw. The seller holds no key to
 * refund on chain, so it records that it owes the buyer the full amount and publishes {@code
 * payments.credit-note-issued.v1} (book SELLER) through the transactional outbox (ADR-0016).
 *
 * <p>Same shape as {@link SettlementRecorder}: a plain {@link EventListener} with one bounded transaction of its own
 * (the {@code credit_note} row and the registry publication commit or roll back together), idempotent per payment key
 * ({@code ON CONFLICT DO NOTHING}; the event is published only when a row was inserted, with the deterministic id
 * {@code eventId(key, "CREDIT_NOTE")}). The buyer already has its response status; a failure here is logged (class
 * name only), counted in {@code saiman.seller.credit_note_record_failures} and never changes the response.
 */
@Component
class CreditNoteRecorder {

    /** The kind in {@link SettlementRecorder#eventId(String, String)} of a credit note. */
    static final String KIND = "CREDIT_NOTE";

    private static final Logger LOG = LoggerFactory.getLogger(CreditNoteRecorder.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher publisher;
    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<ObservationRegistry> observations;
    private final MeterRegistry meters;
    private final Counter amount;
    private final Counter failures;

    CreditNoteRecorder(
            JdbcClient jdbc,
            TransactionTemplate transaction,
            ApplicationEventPublisher publisher,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<ObservationRegistry> observations,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.publisher = publisher;
        this.tracer = tracer;
        this.observations = observations;
        this.meters = meters;
        this.amount = Counter.builder("saiman.seller.credit_note.amount")
                .description("Atomic units (USDC, 6 decimals) the seller owes buyers through credit notes")
                .baseUnit("atomic_units")
                .register(meters);
        this.failures = Counter.builder("saiman.seller.credit_note_record_failures")
                .description("Credit notes that could not be recorded or published")
                .register(meters);
    }

    @EventListener
    void onPaidRequestFailed(X402PaidRequestFailedEvent event) {
        Observation observation = Observation.createNotStarted(
                        "saiman.seller.credit_note.record", observations.getIfAvailable(() -> ObservationRegistry.NOOP))
                .contextualName("credit-note record")
                .lowCardinalityKeyValue("reason", event.reasonCode());
        try {
            observation.observe(() -> record(event));
        } catch (RuntimeException e) {
            failures.increment();
            LOG.error("Credit note record failed: {}", e.getClass().getSimpleName());
        }
    }

    private void record(X402PaidRequestFailedEvent event) {
        AuthorizationRef authorization = new AuthorizationRef(
                event.requirements().network(),
                event.requirements().asset(),
                event.from(),
                event.nonce(),
                Long.parseLong(event.validBefore()));
        long atomicUnits = Long.parseLong(event.value());
        String key = authorization.paymentKey();
        String resource = event.resourceUrl().length() > SettlementRecorder.MAX_RESOURCE_LENGTH
                ? event.resourceUrl().substring(0, SettlementRecorder.MAX_RESOURCE_LENGTH)
                : event.resourceUrl();
        String payTo = event.requirements().payTo();
        EventMetadata meta = new EventMetadata(
                SettlementRecorder.eventId(key, KIND).toString(),
                event.failedAt().truncatedTo(ChronoUnit.MICROS),
                SettlementRecorder.PRODUCER,
                SettlementRecorder.correlationId(tracer.getIfAvailable(), key));
        // Built (and validated) before anything is written: an invalid report throws and nothing is stored.
        CreditNoteIssued creditNote = new CreditNoteIssued(
                meta,
                authorization,
                Money.usdc(atomicUnits),
                payTo,
                resource,
                Book.SELLER,
                event.transactionHash(),
                event.httpStatus(),
                event.reasonCode());
        Boolean inserted = transaction.execute(status -> {
            int rows = jdbc.sql("""
                            INSERT INTO credit_note
                                (payment_key, tx_hash, amount_atomic, pay_to, payer, http_status, reason_code)
                            VALUES (:key, :txHash, :amount, :payTo, :payer, :status, :reason)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("key", key)
                    .param("txHash", creditNote.txHash())
                    .param("amount", atomicUnits)
                    .param("payTo", payTo)
                    .param("payer", authorization.payer())
                    .param("status", creditNote.httpStatus())
                    .param("reason", creditNote.reasonCode())
                    .update();
            if (rows == 1) {
                publisher.publishEvent(creditNote);
            }
            return rows == 1;
        });
        if (Boolean.TRUE.equals(inserted)) {
            meters.counter("saiman.seller.credit_notes", "reason", creditNote.reasonCode())
                    .increment();
            amount.increment((double) atomicUnits);
        }
    }
}
