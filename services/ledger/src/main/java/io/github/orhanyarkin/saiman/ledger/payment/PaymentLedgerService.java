package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.eventing.InboxGuard;
import io.github.orhanyarkin.saiman.ledger.journal.JournalEntry;
import io.github.orhanyarkin.saiman.ledger.journal.JournalRepository;
import io.github.orhanyarkin.saiman.ledger.journal.Posting;
import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.ledger.EntryPosted;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Books one payment fact, exactly once in effect: inbox dedupe, projection lock, {@link PaymentBook}, postings and
 * the {@link EntryPosted} publications all commit in one transaction (CLAUDE.md rule 5, ADR-0016). The
 * publications go into Spring Modulith's registry in that transaction and reach Kafka after commit.
 */
@Service
public class PaymentLedgerService {

    /** The ledger's producer name in {@code meta.producer}, and the prefix of its inbox consumer values. */
    public static final String CONSUMER = "ledger";

    private final InboxGuard inbox;
    private final PaymentRepository payments;
    private final JournalRepository journal;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final MeterRegistry meters;

    public PaymentLedgerService(
            InboxGuard inbox,
            PaymentRepository payments,
            JournalRepository journal,
            ApplicationEventPublisher events,
            Clock clock,
            MeterRegistry meters) {
        this.inbox = inbox;
        this.payments = payments;
        this.journal = journal;
        this.events = events;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Records one fact delivered from {@code topic}.
     *
     * @return the entries posted (empty for a redelivery or a fact that implies nothing new)
     * @throws ConflictingFactException if the fact contradicts the recorded authorization (rolled back)
     */
    @Transactional
    public List<JournalEntry> record(PaymentFact fact, String topic) {
        // The inbox consumer is scoped to the topic: an event id reused on another topic (a forged record on an
        // unauthenticated broker) cannot shadow the real event there.
        if (!inbox.firstDelivery(fact.meta().eventId(), inboxConsumer(topic), topic)) {
            meters.counter("saiman.ledger.events", "topic", topic, "outcome", "duplicate")
                    .increment();
            return List.of();
        }
        PaymentProjection current = payments.insertIfAbsentAndLock(PaymentProjection.initial(fact));
        PaymentBook.Outcome outcome = PaymentBook.apply(current, fact);
        if (!outcome.next().equals(current)) {
            payments.update(outcome.next());
        }
        for (JournalEntry entry : outcome.entries()) {
            journal.post(entry);
            events.publishEvent(entryPosted(entry, fact.meta().correlationId(), clock.instant()));
            meters.counter("saiman.ledger.entries", "kind", entry.kind().name()).increment();
            // Payment-path cost metric: atomic USDC moved per entry kind (one side of the entry).
            meters.counter("saiman.ledger.posted.atomic", "kind", entry.kind().name(), "asset", "USDC")
                    .increment(debitTotal(entry));
        }
        meters.counter("saiman.ledger.events", "topic", topic, "outcome", "booked")
                .increment();
        return outcome.entries();
    }

    /** The inbox consumer value for {@code topic}: {@code ledger:<topic>}. */
    public static String inboxConsumer(String topic) {
        return CONSUMER + ":" + topic;
    }

    /** The {@code ledger.entry-posted.v1} event of a payment entry (event id = entry id, producer {@code ledger}). */
    public static EntryPosted entryPosted(JournalEntry entry, String correlationId, Instant at) {
        return new EntryPosted(
                new EventMetadata(entry.id().toString(), at, CONSUMER, correlationId),
                entry.id(),
                Objects.requireNonNull(entry.paymentId(), "payment entries have a payment id"),
                entry.kind().name(),
                entry.postings().stream().map(Posting::toLine).toList(),
                entry.effectiveAt());
    }

    private static double debitTotal(JournalEntry entry) {
        return entry.postings().stream()
                .filter(p -> p.signedAtomic() > 0)
                .mapToLong(Posting::signedAtomic)
                .sum();
    }
}
