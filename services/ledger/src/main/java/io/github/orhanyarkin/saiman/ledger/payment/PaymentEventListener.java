package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * One listener per {@code payments.*} topic, consumer group {@code ledger}, record acknowledgement (the offset
 * commits after the record's database transaction did). Taking the raw {@link ConsumerRecord} keeps any message
 * converter out of the way: the String value is parsed strictly by {@link PaymentEventParser}. Exceptions go to
 * the container's error handler (bounded retries, then {@code <topic>.ledger-dlt}).
 */
@Component
public class PaymentEventListener {

    private final PaymentEventParser parser;
    private final PaymentLedgerService ledger;

    public PaymentEventListener(PaymentEventParser parser, PaymentLedgerService ledger) {
        this.parser = parser;
        this.ledger = ledger;
    }

    @KafkaListener(id = "ledger-payments-authorized", groupId = "ledger", topics = PaymentTopics.AUTHORIZED)
    void onAuthorized(ConsumerRecord<String, String> record) {
        ledger.record(PaymentFact.of(parser.authorized(record.value())), record.topic());
    }

    @KafkaListener(id = "ledger-payments-settled", groupId = "ledger", topics = PaymentTopics.SETTLED)
    void onSettled(ConsumerRecord<String, String> record) {
        ledger.record(PaymentFact.of(parser.settled(record.value())), record.topic());
    }

    @KafkaListener(id = "ledger-payments-failed", groupId = "ledger", topics = PaymentTopics.FAILED)
    void onFailed(ConsumerRecord<String, String> record) {
        ledger.record(PaymentFact.of(parser.failed(record.value())), record.topic());
    }
}
