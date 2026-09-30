package io.github.orhanyarkin.saiman.ingest.pipeline;

import io.github.orhanyarkin.saiman.ingest.mkk.MkkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Starts the ingest job in the background when {@code saiman.ingest.backfill.enabled=true}. The
 * job runs on its own virtual thread so startup finishes at once and the service can answer
 * retrieval requests while the backfill runs for hours.
 */
@Component
@ConditionalOnProperty(name = "saiman.ingest.backfill.enabled", havingValue = "true")
class BackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);

    private final IngestJob job;

    BackfillRunner(IngestJob job) {
        this.job = job;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("ingest-backfill").start(() -> {
            try {
                RunReport report = job.run();
                log.info(
                        "Backfill finished: aborted={} ({}), failedTickers={}, unknownTickers={}, outcomes={}",
                        report.aborted(),
                        report.abortReason(),
                        report.failedTickers(),
                        report.unknownTickers(),
                        report.outcomes());
            } catch (RuntimeException e) {
                // MKK exception messages carry the HTTP status and MKK error code only, never bodies.
                log.error(
                        "Backfill failed: {}",
                        e instanceof MkkException
                                ? e.getMessage()
                                : e.getClass().getSimpleName());
            }
        });
    }
}
