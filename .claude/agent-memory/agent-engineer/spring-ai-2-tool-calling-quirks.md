---
name: spring-ai-2-tool-calling-quirks
description: Spring AI 2.0.1 tool-calling API pitfalls found in M3 T4b-1 (deprecated toolCallbacks, exception propagation, null tool lists, tracing + unstarted observation)
metadata:
  type: reference
---
- `ChatClientRequestSpec.toolCallbacks(..)` (all overloads) is deprecated for removal in 2.0.1; `tools(Object...)` accepts `ToolCallback`/`ToolCallbackProvider` instances. `toolContext(Map)` is not deprecated; the context reaches `ToolCallback.call(String, ToolContext)` and is never sent to the model.
- `DefaultToolCallingManager` only catches `ToolExecutionException`; any other RuntimeException thrown by a callback propagates out of `ChatClient.call()` — usable to end the tool loop in code.
- `ToolCallingChatOptions.getToolCallbacks()` can be null on prompts without tools.
- ChatClient renders system/user text as a template only when params are given; pass pre-rendered text with no params to avoid re-rendering untrusted values.
- A scripted `ChatModel` must return `ToolCallingChatOptions` from `getOptions()` or the tool loop is not used.
- Router bug (reported, M3): CostAdvisor calls `observation.error()` before `start()` when the cost scope is missing; with Boot tracing the TracingObservationHandler throws IllegalStateException instead of RequestNotSentException.
