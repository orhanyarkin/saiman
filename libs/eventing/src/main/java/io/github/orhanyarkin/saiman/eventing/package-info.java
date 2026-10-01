/**
 * Event plumbing (ADR-0016): producers publish integration events through Spring Modulith's event publication
 * registry (the transactional outbox) and its Kafka externalization; consumers dedupe through {@link InboxGuard}
 * in the same transaction as their state change.
 *
 * <h2>Inbox DDL</h2>
 *
 * Each consuming service copies this into its own Flyway migration (the table lives in the service's schema; the
 * guard uses the connection's default schema):
 *
 * <pre>{@code
 * CREATE TABLE inbox (
 *     event_id    text        NOT NULL,
 *     consumer    text        NOT NULL,
 *     topic       text        NOT NULL,
 *     received_at timestamptz NOT NULL DEFAULT now(),
 *     PRIMARY KEY (event_id, consumer)
 * );
 * }</pre>
 *
 * <h2>Modulith defaults</h2>
 *
 * {@link io.github.orhanyarkin.saiman.eventing.ModulithDefaultsEnvironmentPostProcessor} supplies lowest-precedence
 * defaults for {@code spring.modulith.events.registry-trigger-annotation},
 * {@code ...republish-outstanding-events-on-restart} and {@code ...completion-mode}; set the property anywhere to
 * override.
 */
@NullMarked
package io.github.orhanyarkin.saiman.eventing;

import org.jspecify.annotations.NullMarked;
