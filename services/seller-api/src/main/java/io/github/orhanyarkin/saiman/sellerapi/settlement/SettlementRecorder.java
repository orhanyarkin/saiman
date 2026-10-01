package io.github.orhanyarkin.saiman.sellerapi.settlement;

import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.payments.AuthorizationRef;
import io.github.orhanyarkin.saiman.shared.payments.Book;
import io.github.orhanyarkin.saiman.shared.payments.Finality;
import io.github.orhanyarkin.saiman.shared.payments.PaymentFailed;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import io.github.orhanyarkin.saiman.shared.payments.SettlementEvidence;
import io.github.orhanyarkin.x402.server.X402PaymentFailedEvent;
import io.github.orhanyarkin.x402.server.X402PaymentSettledEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records every settlement outcome the x402 filter reports and publishes it as {@code payments.settled.v1} or
 * {@code payments.failed.v1} (book SELLER) through the transactional outbox (ADR-0016).
 *
 * <p>Plain {@link EventListener}s on purpose: Spring Modulith wraps every {@code @TransactionalEventListener} in a
 * {@code REQUIRES_NEW} completion transaction (ADR-0016), and this listener already needs exactly one transaction of
 * its own, opened here with a {@link TransactionTemplate}: the {@code settlement} row and the registry publication
 * commit or roll back together.
 *
 * <p>The listener runs after the chain has settled and the paid response has been decided, so it never lets a
 * failure escape: a database or publication failure is logged (class name only), counted in {@code
 * saiman.seller.settlement_record_failures}, and the response is untouched. The chain stays the safety net, since
 * the ledger's reconciliation compares its own books with the chain.
 *
 * <p>Event ids derive from the payment key and kind. The row is inserted once per payment key: the same
 * authorization reported twice yields one row and one event. The one exception is an authorization whose settle
 * failed (ambiguous) and later settled (a retry, or the facilitator's late success): the {@code SETTLE_FAILED}
 * row is upgraded to {@code SETTLED} and {@code PaymentSettled} is published as well (its own event id), so the
 * ledger's seller book learns the final outcome. A settled row is never downgraded.
 *
 * <p>The recorder runs on the request thread, so it is bounded: the transaction has a {@value
 * SettlementOutboxConfiguration#RECORD_TIMEOUT_SECONDS} s timeout (also applied to each statement by Spring's
 * JDBC support), and the datasource has a socket timeout and a server-side statement timeout (application.yaml).
 */
@Component
public class SettlementRecorder {

    /** {@link EventMetadata#producer()} of every seller-api event. */
    public static final String PRODUCER = "seller-api";

    private static final Logger LOG = LoggerFactory.getLogger(SettlementRecorder.class);
    private static final Pattern REASON_CODE = Pattern.compile("[a-z0-9_]{1,64}");
    private static final int MAX_RESOURCE_LENGTH = 512;

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher publisher;
    private final ObjectProvider<Tracer> tracer;
    private final Counter failures;

    SettlementRecorder(
            JdbcClient jdbc,
            TransactionTemplate transaction,
            ApplicationEventPublisher publisher,
            ObjectProvider<Tracer> tracer,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.publisher = publisher;
        this.tracer = tracer;
        this.failures = Counter.builder("saiman.seller.settlement_record_failures")
                .description("Settlement outcomes that could not be recorded or published (the chain is the net)")
                .register(meters);
    }

    @EventListener
    void onSettled(X402PaymentSettledEvent event) {
        record(
                event.from(),
                event.nonce(),
                event.value(),
                event.validBefore(),
                event.requirements().network(),
                event.requirements().asset(),
                event.requirements().payTo(),
                event.resourceUrl(),
                event.transactionHash(),
                null,
                event.settledAt(),
                true);
    }

    @EventListener
    void onFailed(X402PaymentFailedEvent event) {
        record(
                event.from(),
                event.nonce(),
                event.value(),
                event.validBefore(),
                event.requirements().network(),
                event.requirements().asset(),
                event.requirements().payTo(),
                event.resourceUrl(),
                null,
                event.errorReason(),
                event.failedAt(),
                false);
    }

    /** The deterministic event id of one (payment key, kind): the same across replays and resubmissions. */
    public static UUID eventId(String paymentKey, String kind) {
        return UUID.nameUUIDFromBytes(
                ("saiman:seller-api:payments:" + paymentKey + ":" + kind).getBytes(StandardCharsets.UTF_8));
    }

    private void record(
            String payer,
            String nonce,
            String value,
            String validBefore,
            String network,
            String asset,
            String payTo,
            String resourceUrl,
            @Nullable String txHash,
            @Nullable String reason,
            Instant occurredAt,
            boolean settled) {
        try {
            AuthorizationRef authorization =
                    new AuthorizationRef(network, asset, payer, nonce, Long.parseLong(validBefore));
            long amount = Long.parseLong(value);
            String key = authorization.paymentKey();
            String resource = resourceUrl.length() > MAX_RESOURCE_LENGTH
                    ? resourceUrl.substring(0, MAX_RESOURCE_LENGTH)
                    : resourceUrl;
            String reasonCode = reasonCode(reason);
            EventMetadata meta = new EventMetadata(
                    eventId(key, settled ? "SETTLED" : "FAILED").toString(),
                    occurredAt.truncatedTo(ChronoUnit.MICROS),
                    PRODUCER,
                    correlationId(key));
            // Built before anything is written: an invalid record throws and nothing is stored.
            Object event = settled
                    ? new PaymentSettled(
                            meta,
                            authorization,
                            Money.usdc(amount),
                            payTo,
                            resource,
                            Book.SELLER,
                            txHash,
                            SettlementEvidence.FACILITATOR,
                            null,
                            null)
                    : new PaymentFailed(
                            meta,
                            authorization,
                            Money.usdc(amount),
                            payTo,
                            resource,
                            Book.SELLER,
                            Finality.AMBIGUOUS,
                            reasonCode,
                            null,
                            null);
            transaction.executeWithoutResult(status -> {
                int inserted = jdbc.sql("""
                                INSERT INTO settlement
                                    (payment_key, tx_hash, amount_atomic, pay_to, payer, outcome, reason_code)
                                VALUES (:key, :txHash, :amount, :payTo, :payer, :outcome, :reason)
                                ON CONFLICT (payment_key) DO UPDATE
                                   SET tx_hash = EXCLUDED.tx_hash, outcome = EXCLUDED.outcome, reason_code = NULL
                                 WHERE settlement.outcome = 'SETTLE_FAILED' AND EXCLUDED.outcome = 'SETTLED'
                                """)
                        .param("key", key)
                        .param("txHash", txHash)
                        .param("amount", amount)
                        .param("payTo", payTo)
                        .param("payer", payer)
                        .param("outcome", settled ? "SETTLED" : "SETTLE_FAILED")
                        .param("reason", settled ? null : reasonCode)
                        .update();
                if (inserted == 1) { // inserted, or upgraded from SETTLE_FAILED
                    publisher.publishEvent(event);
                }
            });
        } catch (RuntimeException e) {
            failures.increment();
            LOG.error("Settlement record failed: {}", e.getClass().getSimpleName());
        }
    }

    private static String reasonCode(@Nullable String reason) {
        if (reason == null) {
            return "unknown";
        }
        return REASON_CODE.matcher(reason).matches() ? reason : "unrecognised";
    }

    /** The request's trace id, else a hash of the payment key; never the nonce or a secret. */
    private String correlationId(String paymentKey) {
        Tracer current = tracer.getIfAvailable();
        if (current != null) {
            Span span = current.currentSpan();
            if (span != null) {
                String traceId = span.context().traceId();
                if (!traceId.isBlank() && traceId.length() <= 64) {
                    return traceId;
                }
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(paymentKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
