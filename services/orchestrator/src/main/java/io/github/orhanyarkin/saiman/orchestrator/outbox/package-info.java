/**
 * Integration events through the transactional outbox (ADR-0016): payment events ({@code payments.*.v1}) and
 * run steps ({@code agent.run-step.v1}) are published with {@code ApplicationEventPublisher} inside the
 * transaction that changes state, persisted by Spring Modulith's JDBC registry and externalized to Kafka
 * (Kafka) after commit. Kafka being down never fails a run: a publication stays incomplete and is resubmitted.
 */
@NullMarked
package io.github.orhanyarkin.saiman.orchestrator.outbox;

import org.jspecify.annotations.NullMarked;
