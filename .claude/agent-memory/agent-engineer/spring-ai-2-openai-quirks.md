---
name: spring-ai-2-openai-quirks
description: Spring AI 2.0.1 OpenAI API shape (openai-java based) and pitfalls found while building libs/model-router
metadata:
  type: reference
---

- `spring-ai-openai` 2.0.1 wraps the official `com.openai:openai-java` client. Build by hand with `OpenAiChatModel.builder().options(OpenAiChatOptions.builder().model(..).apiKey(..).build()).build()` and `OpenAiEmbeddingModel.builder().options(OpenAiEmbeddingOptions.builder().model(..).dimensions(..).apiKey(..).build()).build()`.
- Building without an API key may fail or read env `OPENAI_API_KEY`; wrap in a lazy holder so a keyless app still starts and the first call fails closed.
- `ChatModel.getDefaultOptions()` is deprecated for removal in 2.0.1; do not override it.
- Advisors: `CallAdvisor.adviseCall(ChatClientRequest, CallAdvisorChain)` / `StreamAdvisor.adviseStream(..)`; usage is `response.chatResponse().getMetadata().getUsage()` (Integer tokens). `EmbeddingResponseMetadata.getUsage()` likewise.
- Boot 4.1 auto-config names: `org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration`, `org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration`.
- Tooling: multi-command bash with `cd` + heredoc is rejected in worktrees; use the Write tool and plain single commands.
