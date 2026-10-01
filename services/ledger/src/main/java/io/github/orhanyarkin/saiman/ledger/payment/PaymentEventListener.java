package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * One listener per {@code payments.*} topic, consumer group {@code ledger}, record acknowledgement (the offset
 * commits after the record's database transaction did). Taking the raw {@link ConsumerRecord} keeps any message
 * converter out of the way: the String value is parsed strictly by {@link PaymentEventParser}. Exceptions go to
 * the container's error handler, which classifies them ({@code messaging.RecordFailure}).
 */
@Component
public class PaymentEventListener {

    private final PaymentEventParser parser;
    private final PaymentLedgerService ledger;
    private final ConflictingFactRecorder conflicts;

    public PaymentEventListener(
            PaymentEventParser parser, PaymentLedgerService ledger, ConflictingFactRecorder conflicts) {
        this.parser = parser;
        this.ledger = ledger;
        this.conflicts = conflicts;
    }

    @KafkaListener(id = "ledger-payments-authorized", groupId = "ledger", topics = PaymentTopics.AUTHORIZED)
    void onAuthorized(ConsumerRecord<String, String> record) {
        book(PaymentFact.of(parser.authorized(record.value())), record.topic());
    }

    @KafkaListener(id = "ledger-payments-settled", groupId = "ledger", topics = PaymentTopics.SETTLED)
    void onSettled(ConsumerRecord<String, String> record) {
        book(PaymentFact.of(parser.settled(record.value())), record.topic());
    }

    @KafkaListener(id = "ledger-payments-failed", groupId = "ledger", topics = PaymentTopics.FAILED)
    void onFailed(ConsumerRecord<String, String> record) {
        book(PaymentFact.of(parser.failed(record.value())), record.topic());
    }

    /**
     * Books the fact; a conflicting one first leaves a {@code CONFLICTING_FACT} row (its own transaction, the
     * booking one has rolled back), then goes on to the error handler and the dead-letter topic.
     */
    private void book(PaymentFact fact, String topic) {
        try {
            ledger.record(fact, topic);
        } catch (ConflictingFactException e) {
            if (e.paymentId() != null) {
                conflicts.record(e.paymentId());
            }
            throw e;
        }
    }
}
