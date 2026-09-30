package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Installed by {@link DefaultModelRouter} on every chat client: refuses the call when the daily cap
 * is reached, then adds the cost of the reported usage to the daily counter and the metrics.
 *
 * <p>Cost is read from the usage of the response this advisor sees. If a tool-calling loop runs
 * inside the model, the cost is right only when the model aggregates the usage of all its round
 * trips into that response. The advisor order below just places it close to the model.
 *
 * <p>Accounting failures after the provider has answered (the counter or a meter throws) are logged
 * by exception class only and never lose the paid answer; the next call fails closed at the cap
 * check if the counter is unreadable. A stream that reports no usage at all is counted as {@code
 * no_usage} and logged, not failed.
 */
final class CostAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(CostAdvisor.class);
    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 10;

    private final String tier;
    private final RouterProperties.Price price;
    private final CostGuard guard;
    private final RouterMetrics metrics;

    CostAdvisor(String tier, RouterProperties.Price price, CostGuard guard, RouterMetrics metrics) {
        this.tier = tier;
        this.price = price;
        this.guard = guard;
        this.metrics = metrics;
    }

    @Override
    public String getName() {
        return "modelRouterCost";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        checkCap();
        ChatClientResponse response;
        try {
            response = chain.nextCall(request);
        } catch (RuntimeException e) {
            metrics.call(tier, "error");
            throw e;
        }
        if (!account(response.chatResponse())) {
            metrics.call(tier, "no_usage");
        }
        metrics.call(tier, "ok");
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            checkCap();
            AtomicReference<@Nullable ChatResponse> last = new AtomicReference<>();
            return chain.nextStream(request)
                    .doOnNext(response -> {
                        ChatResponse chat = response.chatResponse();
                        if (chat != null && hasUsage(usageOf(chat))) {
                            last.set(chat);
                        }
                    })
                    .doOnError(e -> metrics.call(tier, "error"))
                    .doOnComplete(() -> {
                        if (!account(last.get())) {
                            metrics.call(tier, "no_usage");
                        }
                        metrics.call(tier, "ok");
                    });
        });
    }

    private void checkCap() {
        try {
            guard.assertUnderCap();
        } catch (DailyCapExceededException e) {
            metrics.call(tier, "cap");
            throw e;
        }
    }

    /** Prices the usage; returns {@code false} if the response carried none (a WARN is logged). */
    private boolean account(@Nullable ChatResponse response) {
        Usage usage = response == null ? null : usageOf(response);
        if (usage == null || !hasUsage(usage)) {
            log.warn("Model call on tier {} reported no token usage; its cost is not counted", tier);
            return false;
        }
        long in = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long out = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        long micros = 0;
        try {
            Money cost = CostCalculator.cost(price, in, out);
            micros = cost.atomicUnits();
            guard.record(cost);
        } catch (RuntimeException e) {
            log.error(
                    "Recording model cost on tier {} failed: {}",
                    tier,
                    e.getClass().getName());
        }
        try {
            metrics.usage(tier, in, out, micros);
        } catch (RuntimeException e) {
            log.error(
                    "Recording model metrics on tier {} failed: {}",
                    tier,
                    e.getClass().getName());
        }
        return true;
    }

    private static boolean hasUsage(@Nullable Usage usage) {
        return usage != null
                && ((usage.getPromptTokens() != null && usage.getPromptTokens() > 0)
                        || (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0));
    }

    private static @Nullable Usage usageOf(ChatResponse response) {
        return response.getMetadata() == null ? null : response.getMetadata().getUsage();
    }
}
