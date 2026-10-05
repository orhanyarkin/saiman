# Payments-engineer memory index

- [Boot 4 test web-client split](boot4-test-web-client-split.md) — TestRestTemplate moved, use @LocalServerPort + RestClient instead in @SpringBootTest(RANDOM_PORT) tests.
- [Boot 4 modularisation gotchas](boot4-modularisation-gotchas.md) — starters split into many small modules; verify class locations against resolved jars, not memory.
- [Boot 4 test standard](boot4-test-standard.md) — RestTestClient, Testcontainers as beans, *Tests naming; supersedes older test-client notes
- [x402 v2 wire format and web3j crypto](x402-v2-and-web3j-crypto.md) — exact spec field names, EIP-712/EIP-3009 signing recipe with web3j 6.0.0, Jackson 3 coordinates, web3j-crypto's surprisingly heavy runtimeClasspath.
- [seller-api RAG testing notes](seller-api-rag-testing-notes.md) — singleton containers, SwitchableRouter, breaker isolation, manual JSON parsing, Bash-tool quirks.
- [Worktree branch sync](worktree-branch-sync.md) — when a worktree is behind the task branch and git checkout/merge get sandbox-blocked, use path-scoped `git checkout <branch> -- <paths>` first.
- [Boot 4 MVC wiring gotchas](boot4-mvc-wiring-gotchas.md) — WebMvcConfigurer circular deps (use ObjectProvider), ContentCachingResponseWrapper's real buffering behaviour, no Redis ConnectionDetailsFactory, ObservationRegistry auto-config ordering, micrometer-core absent from the starter's classpath.
- [Modulith/evm-rpc notes](modulith-evmrpc-notes.md) — Modulith 2.1.1 property formats, Kafka JSON auto-config trap (ByteArray serde, __TypeId__), commit-time trigger errors, ON CONFLICT target pitfall.
- [Spring 7 scheduling and sandbox quirks](spring7-scheduling-and-sandbox-quirks.md) — FixedDelayTask, SchedulingConfigurer gating, NullAway JdbcClient lists, advisory-lock runner, Bash sandbox limits.
- [Worktree Bash + Spring Kafka DLT gotchas](worktree-bash-and-kafka-gotchas.md) — guard-refused shell forms, DLPR headers, back-off loops, Jackson 3 strict, ProblemDetail instance leak, raw Host tests.
- [Spring DAO exception classification](spring-dao-exception-classification.md) — DataAccessResourceFailure is NonTransient (08xxx), spring-kafka 4.1 setBackOffFunction, TestPayment fresh eventId per call.
- [springdoc + JSpecify OpenAPI](springdoc-jspecify-openapi.md) — nullability via ModelConverter, sorted required, Problem Details responses, snapshot-test recipe.
- [Spring 7 Problem Details echo](spring7-problem-details-echo.md) — null bodies in ResponseEntityExceptionHandler, instance = URI, resource 404 bypasses advice, RestTestClient % re-encoding.
- [Standalone MockMvc probes](standalone-mockmvc-probe-controllers.md) — inner @Controller probes for error-path tests; chain_tx_hash means canonical receipt, not party/amount match.
- [M4b audit Lows findings](m4b-audit-lows-findings.md) — PaymentBook not redelivery-idempotent for F/C conflicts; 1 s epoch cushion in window tests; settleMargin() single source.
