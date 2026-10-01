package io.github.orhanyarkin.saiman.sellerapi.settlement;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.seller.outbox.*}: resubmission of publications Kafka did not acknowledge.
 *
 * @param resubmitInterval how often incomplete publications are looked for
 * @param resubmitOlderThan only publications older than this are resubmitted; longer than one bounded send
 *     attempt, so one still in flight is normally not sent twice (a duplicate is harmless: the ledger's inbox
 *     dedupes on the event id)
 */
@ConfigurationProperties("saiman.seller.outbox")
public record SettlementOutboxProperties(
        @DefaultValue("1m") Duration resubmitInterval,
        @DefaultValue("2m") Duration resubmitOlderThan) {

    public SettlementOutboxProperties {
        if (resubmitInterval.isNegative() || resubmitInterval.isZero()) {
            throw new IllegalArgumentException("saiman.seller.outbox.resubmit-interval must be positive");
        }
        if (resubmitOlderThan.isNegative() || resubmitOlderThan.isZero()) {
            throw new IllegalArgumentException("saiman.seller.outbox.resubmit-older-than must be positive");
        }
    }
}
