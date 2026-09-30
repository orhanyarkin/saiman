package io.github.orhanyarkin.saiman.ingest.pipeline;

import io.github.orhanyarkin.saiman.ingest.dlq.DeadLetterRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counters and gauges of the pipeline; embedding tokens and USD are recorded by the model router. */
@Component
public class IngestMetrics {

    private final MeterRegistry registry;

    public IngestMetrics(MeterRegistry registry, DeadLetterRepository deadLetters) {
        this.registry = registry;
        Gauge.builder("ingest.dlq.size", () -> {
                    try {
                        return (double) deadLetters.count();
                    } catch (RuntimeException e) {
                        return Double.NaN;
                    }
                })
                .description("Documents parked in the dead-letter table")
                .register(registry);
    }

    public void document(String outcome) {
        registry.counter("ingest.documents", "outcome", outcome).increment();
    }

    public void chunks(int count) {
        registry.counter("ingest.chunks").increment(count);
    }
}
