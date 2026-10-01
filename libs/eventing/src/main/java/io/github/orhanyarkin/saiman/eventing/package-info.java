/**
 * Event plumbing (ADR-0016): producers publish integration events through Spring Modulith's event publication
 * registry (the transactional outbox) and its Kafka externalization; consumers dedupe through {@link InboxGuard}
 * in the same transaction as their state change.
 */
@NullMarked
package io.github.orhanyarkin.saiman.eventing;

import org.jspecify.annotations.NullMarked;
