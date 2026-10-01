---
name: modulith-evmrpc-notes
description: Verified Spring Modulith 2.1.1 property facts and libs/evm-rpc + libs/eventing gotchas (M4 T1)
metadata:
  type: project
---

- Modulith 2.1.1 properties (verified in spring-modulith-events-core metadata + bytecode): `spring.modulith.events.registry-trigger-annotation` takes a fully-qualified annotation class name (resolved as a class, "Configured type is not an annotation!"); `...republish-outstanding-events-on-restart` boolean; `...completion-mode` = UPDATE|DELETE|ARCHIVE. The reference docs' "@ApplicationModuleListener" wording is not the value format.
- Boot 4 `EnvironmentPostProcessor` is `org.springframework.boot.EnvironmentPostProcessor`, registered in `META-INF/spring.factories` under that name. Lowest-precedence `addLast` MapPropertySource = "defaults the app can override".
- Gradle: the shared convention plugin excludes tag `testnet` on every Test task; a custom task needs `setExcludeTags(HashSet<String>())` (Kotlin `emptySet()` breaks worker serialization: kotlin.collections.EmptySet).
- Resilience4j `IntervalFunction.ofExponentialRandomBackoff` rejects an initial interval < 1 ms.
- AssertJ `assertThat(tx.execute(s -> boolean))` is ambiguous (IntPredicate vs Predicate); wrap in a boolean helper.
- Bash tool in worktrees refuses commands it cannot prove are not git when they use shell loops/variables with unzip/strings, heredocs combined with `cd &&`, or brace-expansion mkdir; use plain commands, the Write tool, or python.
- spring-modulith-events-kafka 2.1.1 (M4 T3): `KafkaJacksonConfiguration` (@ConditionalOnProperty `spring.modulith.events.kafka.enable-json`, default true) adds `@PropertySource kafka-json.properties` that sets producer ByteArraySerializer AND consumer ByteArrayDeserializer, plus a `ByteArrayJacksonJsonMessageConverter` (@ConditionalOnMissingBean RecordMessageConverter). For String JSON: enable-json=false, explicit String (de)serializers, and your own `StringJacksonJsonMessageConverter` overriding `initialRecordHeaders` (otherwise it writes a `__TypeId__` header). The Kafka externalizer is itself an `@ApplicationModuleListener`, so the trigger-annotation default keeps it in the registry. For event types you can't annotate (libs/shared), route with an `EventExternalizationConfiguration` bean: `externalizing().select(..).route(Type, e -> RoutingTarget.forTarget(t).andKey(k)).build()`.
- Commit-time failures of DEFERRABLE constraint triggers surface as `DataIntegrityViolationException` ("JDBC commit; ...") from DataSourceTransactionManager, not TransactionSystemException.
- `INSERT ... ON CONFLICT (col) DO NOTHING` arbitrates only that index; when the PK is derived from the key, racing inserts hit the pkey, so use a target-less `ON CONFLICT DO NOTHING`.
