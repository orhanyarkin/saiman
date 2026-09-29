# Payments-engineer memory index

- [Boot 4 test web-client split](boot4-test-web-client-split.md) — TestRestTemplate moved, use @LocalServerPort + RestClient instead in @SpringBootTest(RANDOM_PORT) tests.
- [Boot 4 modularisation gotchas](boot4-modularisation-gotchas.md) — starters split into many small modules; verify class locations against resolved jars, not memory.
- [Boot 4 test standard](boot4-test-standard.md) — RestTestClient, Testcontainers as beans, *Tests naming; supersedes older test-client notes
- [x402 v2 wire format and web3j crypto](x402-v2-and-web3j-crypto.md) — exact spec field names, EIP-712/EIP-3009 signing recipe with web3j 6.0.0, Jackson 3 coordinates, web3j-crypto's surprisingly heavy runtimeClasspath.
- [Worktree branch sync](worktree-branch-sync.md) — when a worktree is behind the task branch and git checkout/merge get sandbox-blocked, use path-scoped `git checkout <branch> -- <paths>` first.
- [Boot 4 MVC wiring gotchas](boot4-mvc-wiring-gotchas.md) — WebMvcConfigurer circular deps (use ObjectProvider), ContentCachingResponseWrapper's real buffering behaviour, no Redis ConnectionDetailsFactory, ObservationRegistry auto-config ordering, micrometer-core absent from the starter's classpath.
