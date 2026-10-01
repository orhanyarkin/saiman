---
name: seller-api-rag-testing-notes
description: How seller-api RAG tests are wired (singleton Valkey, SwitchableRouter, fake ingest) and sandbox/tooling quirks hit in T4
metadata:
  type: project
---

- seller-api RAG tests extend `testsupport/RagTestBase`: Valkey, FakeFacilitator and FakeIngestServer are JVM singletons started in a static block (NOT `@Container`, which restarts per class and breaks Spring's cached context). A `SwitchableRouter` bean (a `ModelRouter`) makes the auto-configured router back off.
- A shared context means a shared IngestClient circuit breaker: failure-injection tests live in their own context (`IngestOutageEndpointTests`, via `@TestPropertySource`) and stay below the 5-call minimum.
- The model reply is parsed by hand from `ChatClient...content()` (not `.entity()`), so router failures (503) and malformed output (502) stay distinguishable.
- Bash tool in worktree agents refuses some compound commands ("too complex to verify") - heredoc python that contains certain content, or `cd X && python3 <<EOF` followed by more commands. Use Edit/Write tools instead, and keep Bash calls single.
- `SwitchableRouter.replyWith(...)`/`failWith` install a fresh fake model, so `modelCalls()` resets: count per stage, not across reply switches.
- M4b: both RAG endpoints are x402 `upfront`; every in-handler non-2xx (incl. @Valid 400, 429) = 1 settle + 1 credit_note. Test offers must carry `extra.paymentFlow` or the starter answers 402 requirements_mismatch.
- `TransactionTemplate.executeWithoutResult` is the TransactionOperations default that calls `execute`: a `@MockitoSpyBean` stub on `execute(any())` fails both the settlement and the credit-note recorder.
- `ChatClient.call().content()` is `@Nullable` under NullAway; `String.split(x)` triggers Error Prone StringSplitter (use the two-arg form).
