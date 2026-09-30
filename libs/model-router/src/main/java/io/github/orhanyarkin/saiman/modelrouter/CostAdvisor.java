package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
import reactor.core.publisher.SignalType;

/**
 * Installed by {@link DefaultModelRouter} on every chat client. Before the call it reserves the
 * worst-case cost against the daily cap ({@link CostGuard#reserve}); after the call it settles the
 * reservation to the actual cost from the reported usage.
 *
 * <p>The reservation stays as the charge whenever the actual cost is unknown: the call failed after
 * being sent, a stream was cancelled or errored before usage arrived, the response carried no usage
 * or unusable usage (negative, overflowing). It is given back only when the request provably never
 * left ({@link RequestNotSentException}). Streams settle in {@code doFinally}, so completion,
 * cancellation and errors are all accounted.
 *
 * <p>Cost is read from the usage of the response this advisor sees. If a tool-calling loop runs
 * inside the model, the cost is right only when the model aggregates the usage of all its round
 * trips into that response. Accounting failures after the provider has answered are logged by
 * exception class only and never lose the paid answer.
 */
final class CostAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(CostAdvisor.class);
    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 10;

    private final RouteCosting costing;
    private final String tier;
    private final CostGuard guard;
    private final RouterMetrics metrics;

    CostAdvisor(RouteCosting costing, CostGuard guard, RouterMetrics metrics) {
        this.costing = costing;
        this.tier = costing.label();
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
        CostGuard.Reservation reservation = reserve(request);
        ChatClientResponse response;
        try {
            response = chain.nextCall(request);
        } catch (RuntimeException e) {
            afterFailure(reservation, e);
            metrics.call(tier, "error");
            throw e;
        }
        settle(reservation, response.chatResponse());
        metrics.call(tier, "ok");
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            CostGuard.Reservation reservation = reserve(request);
            AtomicReference<@Nullable ChatResponse> last = new AtomicReference<>();
            AtomicBoolean notSent = new AtomicBoolean();
            Flux<ChatClientResponse> upstream;
            try {
                upstream = chain.nextStream(request);
            } catch (RuntimeException e) {
                afterFailure(reservation, e);
                throw e;
            }
            // Settled exactly once, on the first of complete / error / cancel. doOnComplete and
            // doOnError run before the signal reaches the subscriber, so a caller that has seen the
            // end of the stream also sees the settled counter; doFinally is the safety net.
            AtomicBoolean finished = new AtomicBoolean();
            Consumer<SignalType> finish = signal -> {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                if (notSent.get()) {
                    release(reservation);
                    return;
                }
                settle(reservation, last.get());
                if (signal == SignalType.ON_COMPLETE) {
                    metrics.call(tier, "ok");
                } else if (signal == SignalType.CANCEL) {
                    metrics.call(tier, "cancelled");
                }
            };
            return upstream.doOnNext(response -> {
                        ChatResponse chat = response.chatResponse();
                        if (chat != null && usableUsage(chat) != null) {
                            last.set(chat);
                        }
                    })
                    .doOnComplete(() -> finish.accept(SignalType.ON_COMPLETE))
                    .doOnError(e -> {
                        if (e instanceof RequestNotSentException) {
                            notSent.set(true);
                        }
                        metrics.call(tier, "error");
                        finish.accept(SignalType.ON_ERROR);
                    })
                    .doOnCancel(() -> finish.accept(SignalType.CANCEL))
                    .doFinally(finish);
        });
    }

    private CostGuard.Reservation reserve(ChatClientRequest request) {
        try {
            return guard.reserve(costing.estimate(request.prompt()));
        } catch (DailyCapExceededException e) {
            metrics.call(tier, "cap");
            throw e;
        }
    }

    private void afterFailure(CostGuard.Reservation reservation, RuntimeException failure) {
        if (failure instanceof RequestNotSentException) {
            release(reservation);
        }
        // otherwise the outcome is unknown: the reservation stays as the charge
    }

    private void release(CostGuard.Reservation reservation) {
        try {
            guard.release(reservation);
        } catch (RuntimeException e) {
            log.error(
                    "Releasing a cost reservation on {} failed: {}",
                    tier,
                    e.getClass().getName());
        }
    }

    /** Settles to the actual cost, or keeps the estimate when the usage is missing or unusable. */
    private void settle(CostGuard.Reservation reservation, @Nullable ChatResponse response) {
        Usage usage = response == null ? null : usableUsage(response);
        if (response == null || usage == null) {
            keepEstimate(reservation, "reported no usable token usage");
            return;
        }
        long in = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long out = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        Money actual;
        try {
            actual = costing.actual(in, out, response.getMetadata().getModel());
        } catch (RuntimeException e) {
            keepEstimate(reservation, "reported unusable token usage");
            return;
        }
        try {
            guard.settle(reservation, actual);
        } catch (RuntimeException e) {
            log.error("Settling model cost on {} failed: {}", tier, e.getClass().getName());
        }
        try {
            metrics.usage(tier, in, out, actual.atomicUnits());
        } catch (RuntimeException e) {
            log.error(
                    "Recording model metrics on {} failed: {}",
                    tier,
                    e.getClass().getName());
        }
    }

    private void keepEstimate(CostGuard.Reservation reservation, String reason) {
        log.warn("Model call on {} {}; the worst-case estimate is charged instead", tier, reason);
        try {
            metrics.call(tier, "no_usage");
            metrics.usage(tier, 0, 0, reservation.estimate().atomicUnits());
        } catch (RuntimeException e) {
            log.error(
                    "Recording model metrics on {} failed: {}",
                    tier,
                    e.getClass().getName());
        }
    }

    /** The response's usage if it has non-negative counts and at least one token, else {@code null}. */
    private static @Nullable Usage usableUsage(ChatResponse response) {
        Usage usage =
                response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return null;
        }
        Integer in = usage.getPromptTokens();
        Integer out = usage.getCompletionTokens();
        boolean negative = (in != null && in < 0) || (out != null && out < 0);
        boolean any = (in != null && in > 0) || (out != null && out > 0);
        return !negative && any ? usage : null;
    }
}
