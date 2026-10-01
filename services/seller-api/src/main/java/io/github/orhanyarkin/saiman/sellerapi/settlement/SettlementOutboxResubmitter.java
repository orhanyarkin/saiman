package io.github.orhanyarkin.saiman.sellerapi.settlement;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resends publications Kafka did not acknowledge while the process keeps running ({@code
 * republish-outstanding-events-on-restart} covers a restart) and exposes the backlog as the gauge {@code
 * saiman.outbox.incomplete}.
 */
@Component
class SettlementOutboxResubmitter {

    private static final Logger LOG = LoggerFactory.getLogger(SettlementOutboxResubmitter.class);

    private final IncompleteEventPublications incomplete;
    private final SettlementOutboxProperties properties;
    private final JdbcClient jdbc;
    private final AtomicLong backlog = new AtomicLong();

    SettlementOutboxResubmitter(
            IncompleteEventPublications incomplete,
            SettlementOutboxProperties properties,
            JdbcClient jdbc,
            MeterRegistry meters) {
        this.incomplete = incomplete;
        this.properties = properties;
        this.jdbc = jdbc;
        Gauge.builder("saiman.outbox.incomplete", backlog, AtomicLong::get)
                .description("Event publications not yet acknowledged by Kafka (as of the last resubmit pass)")
                .register(meters);
    }

    @Scheduled(
            fixedDelayString = "${saiman.seller.outbox.resubmit-interval:1m}",
            initialDelayString = "${saiman.seller.outbox.resubmit-interval:1m}")
    void resubmit() {
        try {
            long outstanding = countIncomplete();
            backlog.set(outstanding);
            if (outstanding == 0) {
                return;
            }
            LOG.info(
                    "Resubmitting event publications older than {} ({} incomplete)",
                    properties.resubmitOlderThan(),
                    outstanding);
            incomplete.resubmitIncompletePublicationsOlderThan(properties.resubmitOlderThan());
        } catch (RuntimeException e) {
            // A database outage must not kill the schedule; the next pass tries again.
            LOG.warn("Outbox resubmission failed: {}", e.getClass().getSimpleName());
        }
    }

    long countIncomplete() {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class)
                .single();
    }
}
