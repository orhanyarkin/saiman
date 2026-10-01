package io.github.orhanyarkin.saiman.orchestrator.outbox;

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
 * Resends publications that Kafka did not acknowledge (broker down, timeout) while the process keeps running;
 * {@code republish-outstanding-events-on-restart} covers a restart. Also exposes the backlog as the gauge
 * {@code saiman.outbox.incomplete}, so a broker outage is visible.
 */
@Component
class OutboxResubmitter {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxResubmitter.class);

    private final IncompleteEventPublications incomplete;
    private final OutboxProperties properties;
    private final JdbcClient jdbc;
    private final AtomicLong backlog = new AtomicLong();

    OutboxResubmitter(
            IncompleteEventPublications incomplete,
            OutboxProperties properties,
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
            fixedDelayString = "${saiman.orchestrator.outbox.resubmit-interval:1m}",
            initialDelayString = "${saiman.orchestrator.outbox.resubmit-interval:1m}")
    void resubmit() {
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
    }

    long countIncomplete() {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class)
                .single();
    }
}
