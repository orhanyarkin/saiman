---
name: spring-ai-tool-loop-quirks
description: Spring AI 2.0.1 tool-calling loop facts that decide whether tools reach the provider and whether advisor params survive iterations
metadata:
  type: reference
---

- `DefaultChatClient` builds every request from `chatModel.getOptions().mutate()`; the `ChatModel.getOptions()` default is plain `ChatOptions`, so a model that does not override it silently DROPS `.tools(...)`/tool callbacks (no error, the loop just never runs). Custom/wrapper `ChatModel`s (router's OpenAI lazy + failover models) must return tool-capable options; test fakes need `ToolCallingChatOptions.builder().build()`.
- `ToolCallingAdvisor` is auto-registered (order HIGHEST+300); do not add a second (only one `ToolAdvisor` allowed). An advisor with order LOWEST-10 runs inside the loop, once per round trip, and sees the same `request.context()` (advisor params) every iteration.
- There is no `internalToolExecutionEnabled` in 2.0; only the advisor executes tools.
- openai-java errors: `OpenAIServiceException.statusCode()` (RateLimit=429, InternalServer=5xx), `OpenAIIoException` for connect/timeout. SDK `maxRetries` retries before the exception surfaces; set 0 in stub-server tests.
- Fallback model inherits the prompt's seeded options: the caller-seeded max-completion-tokens (primary's) wins if lower than the backup's limit (wireOptions clamps downward only).
- Sandbox: heredoc python edits with many quotes sometimes get refused ("too complex"); write the script with the Write tool into the scratchpad and run it.
