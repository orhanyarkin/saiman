package io.github.orhanyarkin.saiman.orchestrator.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.orchestrator.outbox.*}: resubmission of publications Kafka did not acknowledge.
 *
 * @param resubmitInterval how often incomplete publications are looked for
 * @param resubmitOlderThan only publications older than this are resubmitted; longer than one bounded send
 *     attempt ({@code max.block.ms} + {@code delivery.timeout.ms}), so one still in flight is normally not sent
 *     twice (a duplicate would be harmless: consumers dedupe on the event id)
 */
@ConfigurationProperties("saiman.orchestrator.outbox")
public record OutboxProperties(
        @DefaultValue("1m") Duration resubmitInterval,
        @DefaultValue("2m") Duration resubmitOlderThan) {

    public OutboxProperties {
        if (resubmitInterval.isNegative() || resubmitInterval.isZero()) {
            throw new IllegalArgumentException("saiman.orchestrator.outbox.resubmit-interval must be positive");
        }
        if (resubmitOlderThan.isNegative() || resubmitOlderThan.isZero()) {
            throw new IllegalArgumentException("saiman.orchestrator.outbox.resubmit-older-than must be positive");
        }
    }
}
