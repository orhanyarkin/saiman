package io.github.orhanyarkin.saiman.orchestrator.outbox;

import io.github.orhanyarkin.saiman.orchestrator.payment.IntentAuthorization;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.github.orhanyarkin.saiman.shared.payments.SettlementEvidence;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the payment events of intents signed before the outbox existed (M3 history), so the ledger can
 * reconcile them: {@code PaymentAuthorized} for every SIGNED, SETTLED or HELD intent, and {@code PaymentSettled}
 * (FACILITATOR, or CHAIN if the resolver settled it) for every SETTLED one. Runs at startup after {@code
 * SpendRecovery} (which turns SIGNED into HELD).
 *
 * <p>Exactly once: it publishes only what has no {@code payment_event_log} row, and each publication inserts that
 * row in its own transaction (one per intent and kind), so a crash midway resumes where it stopped and later
 * startups find nothing to do. Live publications write the same rows, so nothing is published twice.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class PaymentEventBackfill implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentEventBackfill.class);

    private final PaymentIntentService intents;
    private final PaymentEventPublisher events;
    private final TransactionTemplate tx;

    PaymentEventBackfill(
            PaymentIntentService intents, PaymentEventPublisher events, PlatformTransactionManager transactionManager) {
        this.intents = intents;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        backfill();
    }

    /** One pass (package-private so tests can run it again); returns how many events it published. */
    int backfill() {
        int published = 0;
        List<UUID> authorized = intents.findUnpublished(
                PaymentEventPublisher.Kind.AUTHORIZED.name(), List.of("SIGNED", "SETTLED", "HELD", "RELEASED"));
        for (UUID id : authorized) {
            published += publish(id, PaymentEventPublisher.Kind.AUTHORIZED);
        }
        List<UUID> settled = intents.findUnpublished(PaymentEventPublisher.Kind.SETTLED.name(), List.of("SETTLED"));
        for (UUID id : settled) {
            published += publish(id, PaymentEventPublisher.Kind.SETTLED);
        }
        if (published > 0) {
            LOG.info("Backfilled {} payment event(s) of earlier payment intents", published);
        }
        return published;
    }

    private int publish(UUID id, PaymentEventPublisher.Kind kind) {
        return Objects.requireNonNull(tx.execute(status -> {
            IntentAuthorization intent = intents.lockAuthorization(id).orElse(null);
            if (intent == null) {
                return 0;
            }
            if (kind == PaymentEventPublisher.Kind.AUTHORIZED) {
                return events.authorized(intent) ? 1 : 0;
            }
            if (intent.status() != PaymentIntentStatus.SETTLED) {
                return 0;
            }
            String txHash = intent.txHash();
            SettlementEvidence evidence =
                    resolvedByChain(id) ? SettlementEvidence.CHAIN : SettlementEvidence.FACILITATOR;
            if (txHash == null && evidence == SettlementEvidence.FACILITATOR) {
                LOG.warn("Settled payment intent {} has no tx hash; its PaymentSettled is not backfilled", id);
                return 0;
            }
            return events.settled(intent, txHash, evidence) ? 1 : 0;
        }));
    }

    private boolean resolvedByChain(UUID id) {
        return intents.resolvedBy(id).filter("CHAIN"::equals).isPresent();
    }
}
