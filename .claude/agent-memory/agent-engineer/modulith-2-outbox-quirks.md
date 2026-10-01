---
name: modulith-2-outbox-quirks
description: Spring Modulith 2.1.1 outbox gotchas found wiring the orchestrator (M4 T4): completion advisor vs @TransactionalEventListener, early bean creation, Kafka value format
metadata:
  type: reference
---

- `CompletionRegisteringAdvisor` wraps EVERY `@TransactionalEventListener(phase=AFTER_COMMIT)` (pointcut ignores `registry-trigger-annotation`) and calls `markProcessing` in REQUIRES_NEW from afterCompletion while the committing thread still holds its connection: 2 connections per committing thread, Hikari starvation under concurrency (a 16-thread append test went 60s -> 360s). Fix: in-process listeners as plain `@EventListener` + `TransactionSynchronization.afterCommit` (orchestrator `events/AfterCommit`).
- `EventExternalizationConfiguration` is pulled in by a static `EventListenerFactory` very early: declare the @Bean `static` and resolve `JsonMapper` lazily via `ObjectProvider`, else Boot's Jackson config fails ("No default constructor found").
- Externalizer: `EventExternalizerModuleListener` is `@ApplicationModuleListener(propagation=SUPPORTS)`, async. Routing (`route`) reads the original event, `mapping` only changes the payload. Kafka transport sends `Message` via `KafkaTemplate.send(Message)`; for String JSON without `__TypeId__`: `spring.modulith.events.kafka.enable-json=false`, StringSerializer, and a `StringJacksonJsonMessageConverter` bean overriding `initialRecordHeaders` (map non-String payloads; a String payload would get JSON-quoted).
- JDBC registry v2 schema (status, completion_attempts, last_resubmission_date) is the default; `spring.modulith.events.jdbc.schema` prefixes the table. Failed sends stay with completion_date NULL; `IncompleteEventPublications.resubmitIncompletePublicationsOlderThan` resends them (verified against Redpanda pause/unpause).
- Testcontainers: pausing the Redpanda container (`getDockerClient().pauseContainerCmd`) is a cheap, port-stable "broker down" for outbox tests; without a broker, set `spring.kafka.admin.auto-create=false` and small `max.block.ms` so contexts don't wait 30 s.
