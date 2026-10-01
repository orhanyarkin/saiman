package io.github.orhanyarkin.saiman.orchestrator.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * A chat model that answers from a script: a queue of replies consumed one per round trip, then an
 * optional fallback function (for adversarial models that answer every prompt the same way). Each
 * reply reports a token usage, so the router prices it like a real call. Every prompt is recorded
 * with the route model it was sent to. Thread-safe enough for one run at a time.
 *
 * <p>Use it through {@link ScriptedModels}: {@code ScriptedModels.router(..)} for plain unit tests,
 * {@link ScriptedModels.Config} to put it behind the real auto-configured router in a Spring test.
 */
public final class ScriptedChatModel {

    /** One scripted answer: an assistant message (text or tool calls) and its reported usage. */
    public record Reply(AssistantMessage message, int promptTokens, int completionTokens) {

        public static Reply text(String text) {
            return new Reply(new AssistantMessage(text), DEFAULT_IN, DEFAULT_OUT);
        }

        /** A request to call {@code tool} with raw JSON {@code arguments}. */
        public static Reply toolCall(String tool, String arguments) {
            return new Reply(
                    AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "call-" + UUID.randomUUID(), "function", tool, arguments)))
                            .build(),
                    DEFAULT_IN,
                    DEFAULT_OUT);
        }

        public Reply withUsage(int in, int out) {
            return new Reply(message, in, out);
        }
    }

    /** What the model saw on one round trip. */
    public record Seen(String routeModel, Prompt prompt) {

        /** Every message text of the prompt, joined (system, user, assistant, tool results). */
        public String text() {
            StringBuilder all = new StringBuilder();
            for (Message message : prompt.getInstructions()) {
                all.append(message.getText()).append('\n');
                if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage tool) {
                    tool.getResponses()
                            .forEach(r -> all.append(r.responseData()).append('\n'));
                }
            }
            return all.toString();
        }

        /** Names of the tools offered to the model on this round trip. */
        public List<String> toolNames() {
            if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
                    || options.getToolCallbacks() == null) {
                return List.of();
            }
            return options.getToolCallbacks().stream()
                    .map(c -> c.getToolDefinition().name())
                    .toList();
        }
    }

    static final int DEFAULT_IN = 1_000;
    static final int DEFAULT_OUT = 200;

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile @Nullable Function<Prompt, Reply> fallback;

    /** Queues replies, consumed in order. */
    public synchronized ScriptedChatModel then(Reply... replies) {
        for (Reply reply : replies) {
            script.addLast(reply);
        }
        return this;
    }

    /** Queues a failure (for example a provider error) for the next round trip. */
    public synchronized ScriptedChatModel thenFail(RuntimeException failure) {
        script.addLast(failure);
        return this;
    }

    /** Answers every round trip after the queue with {@code reply} (adversarial or looping models). */
    public ScriptedChatModel otherwise(Function<Prompt, Reply> reply) {
        this.fallback = reply;
        return this;
    }

    public synchronized void reset() {
        script.clear();
        seen.clear();
        fallback = null;
    }

    public List<Seen> seen() {
        return List.copyOf(seen);
    }

    public int callCount() {
        return seen.size();
    }

    /** The model as one route sees it (the router builds one model per route). */
    public ChatModel forRoute(String routeModel) {
        return new ChatModel() {
            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build(); // tool-capable, like the OpenAI routes
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                return answer(routeModel, prompt);
            }
        };
    }

    private ChatResponse answer(String routeModel, Prompt prompt) {
        seen.add(new Seen(routeModel, prompt));
        Object next;
        synchronized (this) {
            next = script.pollFirst();
        }
        if (next instanceof RuntimeException failure) {
            throw failure;
        }
        Reply reply = (Reply) next;
        if (reply == null) {
            Function<Prompt, Reply> otherwise = fallback;
            if (otherwise == null) {
                throw new IllegalStateException("the model script is exhausted");
            }
            reply = otherwise.apply(prompt);
        }
        return ChatResponse.builder()
                .generations(List.of(new Generation(reply.message())))
                .metadata(ChatResponseMetadata.builder()
                        .model(routeModel)
                        .usage(new DefaultUsage(reply.promptTokens(), reply.completionTokens()))
                        .build())
                .build();
    }
}
