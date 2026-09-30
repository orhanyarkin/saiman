package io.github.orhanyarkin.saiman.ingest.dlq;

import io.github.orhanyarkin.saiman.ingest.pipeline.IngestBusyException;
import io.github.orhanyarkin.saiman.ingest.pipeline.IngestJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoint behind {@code make ingest-retry-dlq}. Like all of {@code /internal}, it is only
 * Answers 409 Conflict while an ingest run holds the advisory lock: nothing is reset then, retry
 * later. Reachable on the compose network and 127.0.0.1 (ADR-0012); it must never be routed publicly.
 */
@RestController
@RequestMapping("/internal/v1/admin")
class DeadLetterController {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterController.class);

    /** @param reopened how many parked documents were reset; the resumed run starts in the background */
    record RetryResponse(int reopened) {}

    private final DeadLetterService service;
    private final IngestJob job;

    DeadLetterController(DeadLetterService service, IngestJob job) {
        this.service = service;
        this.job = job;
    }

    @PostMapping("/retry-dlq")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RetryResponse retry() {
        if (job.isRunning()) {
            throw new IngestBusyException();
        }
        int reopened = service.retry();
        if (reopened > 0) {
            Thread.ofVirtual().name("ingest-dlq-retry").start(() -> {
                try {
                    job.run();
                } catch (RuntimeException e) {
                    log.error("DLQ retry run failed: {}", e.getClass().getSimpleName());
                }
            });
        }
        return new RetryResponse(reopened);
    }
}
