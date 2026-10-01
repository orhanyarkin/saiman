package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.payments.AuthorizationRef;
import io.github.orhanyarkin.saiman.shared.payments.Book;
import io.github.orhanyarkin.saiman.shared.payments.CreditNoteIssued;
import io.github.orhanyarkin.saiman.shared.payments.PaymentAuthorized;
import io.github.orhanyarkin.saiman.shared.payments.PaymentFailed;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One thing a producer reported about an authorization: the input of {@link PaymentBook}. Wraps the shared
 * {@code payments.*} records so the state machine can switch over them exhaustively.
 */
public sealed interface PaymentFact {

    EventMetadata meta();

    AuthorizationRef authorization();

    Money amount();

    String payTo();

    @Nullable
    UUID paymentIntentId();

    @Nullable
    UUID runId();

    default String paymentKey() {
        return authorization().paymentKey();
    }

    static Authorized of(PaymentAuthorized event) {
        return new Authorized(event);
    }

    static Settled of(PaymentSettled event) {
        return new Settled(event);
    }

    static Failed of(PaymentFailed event) {
        return new Failed(event);
    }

    static CreditNoted of(CreditNoteIssued event) {
        return new CreditNoted(event);
    }

    /** {@code payments.authorized.v1} (buyer). */
    record Authorized(PaymentAuthorized event) implements PaymentFact {
        @Override
        public EventMetadata meta() {
            return event.meta();
        }

        @Override
        public AuthorizationRef authorization() {
            return event.authorization();
        }

        @Override
        public Money amount() {
            return event.amount();
        }

        @Override
        public String payTo() {
            return event.payTo();
        }

        @Override
        public UUID paymentIntentId() {
            return event.paymentIntentId();
        }

        @Override
        public UUID runId() {
            return event.runId();
        }
    }

    /** {@code payments.settled.v1} for one book. */
    record Settled(PaymentSettled event) implements PaymentFact {
        @Override
        public EventMetadata meta() {
            return event.meta();
        }

        @Override
        public AuthorizationRef authorization() {
            return event.authorization();
        }

        @Override
        public Money amount() {
            return event.amount();
        }

        @Override
        public String payTo() {
            return event.payTo();
        }

        @Override
        public @Nullable UUID paymentIntentId() {
            return event.paymentIntentId();
        }

        @Override
        public @Nullable UUID runId() {
            return event.runId();
        }

        public Book book() {
            return event.book();
        }
    }

    /** {@code payments.failed.v1} for one book. */
    record Failed(PaymentFailed event) implements PaymentFact {
        @Override
        public EventMetadata meta() {
            return event.meta();
        }

        @Override
        public AuthorizationRef authorization() {
            return event.authorization();
        }

        @Override
        public Money amount() {
            return event.amount();
        }

        @Override
        public String payTo() {
            return event.payTo();
        }

        @Override
        public @Nullable UUID paymentIntentId() {
            return event.paymentIntentId();
        }

        @Override
        public @Nullable UUID runId() {
            return event.runId();
        }

        public Book book() {
            return event.book();
        }
    }

    /**
     * {@code payments.credit-note-issued.v1} (seller, ADR-0021): settled up front, then not served. Always the
     * seller's book; carries the settlement's tx hash. No intent or run: the seller never knows the buyer's.
     */
    record CreditNoted(CreditNoteIssued event) implements PaymentFact {
        @Override
        public EventMetadata meta() {
            return event.meta();
        }

        @Override
        public AuthorizationRef authorization() {
            return event.authorization();
        }

        @Override
        public Money amount() {
            return event.amount();
        }

        @Override
        public String payTo() {
            return event.payTo();
        }

        @Override
        public @Nullable UUID paymentIntentId() {
            return null;
        }

        @Override
        public @Nullable UUID runId() {
            return null;
        }

        public String txHash() {
            return event.txHash();
        }
    }
}
