# ADR-0016: Transactional outbox with Spring Modulith; inbox in each consumer

Status: Accepted (2026-10-01). Implements CLAUDE.md rule 5 for M4.

## Context
Payment state changes in the orchestrator and seller-api must reach the ledger exactly once in effect, even when Kafka (Redpanda) or a service is down. The human prefers well-known libraries over hand-rolled infrastructure.

## Decision
- **Producers** (orchestrator, seller-api; the ledger for its own events) publish integration events with `ApplicationEventPublisher` inside the transaction that changes state. **Spring Modulith 2.1** (built against Boot 4.1 / Spring Kafka 4.1) persists them in its JDBC event publication registry (`spring-modulith-starter-jdbc`, table in the producer's own schema) and externalizes them to Kafka (`spring-modulith-events-kafka`, `@Externalized("<topic>::#{<key expression>}")`). Incomplete publications are republished on restart (`spring.modulith.events.republish-outstanding-events-on-restart=true`) and completed ones are purged after a retention period.
- **Scope of the registry:** `spring.modulith.events.registry-trigger-annotation` is set to `org.springframework.modulith.events.ApplicationModuleListener` (a fully-qualified class name), so the orchestrator's existing in-process `@TransactionalEventListener`s (`RunEventBus`, `ApprovalWaiter`) are not persisted or replayed.
- **Consumers** (the ledger) dedupe through an `inbox` table keyed by `(event_id, consumer)` written in the same transaction as the postings (`InboxGuard`, `libs/eventing`). Business keys (`UNIQUE (payment_key, book, kind)`) are the second layer.
- **Ordering:** not relied on. Externalization is asynchronous and a republish can reorder events, so the ledger is order-independent: an event posts every entry its state implies that is not posted yet (property P2, ADR-0019). Message keys are the payment key (payments) or run id (run steps) so a partition keeps related events together when it can.
- **Wire format:** JSON with Boot's Jackson mapper; `Money` as `{atomicUnits, asset, decimals}` with integer `atomicUnits` (≤ 2^53-1); each event carries `EventMetadata` (event id, time, producer, correlation id). Schemas and golden fixtures live in `docs/events` and `libs/shared` tests.
- `agent.run-step.v1` is externalized the same way from the transaction that appends a `run_event` row.

## Alternatives
- Hand-rolled outbox table + `@Scheduled` relay with `FOR UPDATE SKIP LOCKED`: full control of ordering, but more code to own and explain, and ordering is not needed once the consumer is order-independent.
- Direct `KafkaTemplate` sends after commit: loses events when the broker is down (violates rule 5).
- Debezium/CDC: a new piece of infrastructure.

## Consequences
+ A recognised, documented outbox with retries and a registry that operations can inspect.
+ The consumer design (inbox + business keys + order independence) is robust to duplicates and reordering, which the acceptance tests prove.
− Delivery order is not guaranteed; consumers must stay order-independent.
− One more dependency line (Spring Modulith BOM) to keep in step with Boot.
