package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.shared.ledger.MismatchKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leaves an auditable trace of a quarantined fact: a {@code CONFLICTING_FACT} row in {@code reconciliation_mismatch}
 * (no run, one per payment), written in its own transaction before the record goes to the dead-letter topic. The
 * booking transaction has already rolled back by then, so the DLT is not the only place a human can find it. No
 * {@code ledger.reconciliation-mismatch.v1} event is published: that contract requires a reconciliation run id.
 */
@Component
public class ConflictingFactRecorder {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final MeterRegistry meters;

    public ConflictingFactRecorder(JdbcClient jdbc, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.meters = meters;
    }

    /** Records the conflict once per payment; a database failure propagates (the record is then retried). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID paymentId) {
        String kind = MismatchKind.CONFLICTING_FACT.name();
        int inserted = jdbc.sql("""
                        INSERT INTO reconciliation_mismatch (id, run_id, payment_id, kind, detected_at)
                        VALUES (:id, NULL, :paymentId, :kind, :at)
                        ON CONFLICT (payment_id, kind) DO NOTHING
                        """)
                .param(
                        "id",
                        UUID.nameUUIDFromBytes(
                                ("saiman-ledger:mismatch:" + paymentId + ":" + kind).getBytes(StandardCharsets.UTF_8)))
                .param("paymentId", paymentId)
                .param("kind", kind)
                .param("at", Timestamp.from(clock.instant()))
                .update();
        if (inserted == 1) {
            meters.counter("saiman.ledger.reconciliation.mismatches", "kind", kind)
                    .increment();
        }
    }
}
