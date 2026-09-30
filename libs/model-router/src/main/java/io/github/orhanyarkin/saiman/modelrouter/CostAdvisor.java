package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
 * <p>With a {@link ScopedCostGuard} the same estimate is first reserved against the scope named by
 * {@link RouterAdvisorParams#COST_SCOPE} (a run), then against the global day; if the global
 * reservation fails the scope reservation is given back. The scope lives in the request context, which
 * Spring AI's {@code ToolCallingAdvisor} hands to every iteration of its loop (this advisor sits
 * inside that loop), so every round trip of a tool-calling run is charged under the same scope.
 * With {@code require-cost-scope} an unscoped call fails before anything is sent.
 *
 * <p>Every round trip is one {@code saiman.model.call} observation (see {@link #OBSERVATION}).
 *
 * <p>Cost is read from the usage of the response this advisor sees. If a tool-calling loop runs
 * inside the model, the cost is right only when the model aggregates the usage of all its round
 * trips into that response. Accounting failures after the provider has answered are logged by
 * exception class only and never lose the paid answer.
 */
final class CostAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(CostAdvisor.class);
    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 10;

    /** Name of the observation of one model round trip. */
    static final String OBSERVATION = "saiman.model.call";

    /** Scope settings of a router: the optional guard, whether a scope is mandatory, the budget ceiling. */
    record ScopePolicy(@Nullable ScopedCostGuard guard, boolean required, long maxBudgetUsdMicros) {
        static final ScopePolicy NONE = new ScopePolicy(null, false, 0);
    }

    /** What one round trip holds: the global reservation, the scope reservation and the observation. */
    private static final class Held {
        final CostGuard.Reservation global;
        final ScopedCostGuard.@Nullable ScopeReservation scoped;
        final @Nullable String scope;
        final Observation observation;
        private final AtomicBoolean observed = new AtomicBoolean();

        Held(
                CostGuard.Reservation global,
                ScopedCostGuard.@Nullable ScopeReservation scoped,
                @Nullable String scope,
                Observation observation) {
            this.global = global;
            this.scoped = scoped;
            this.scope = scope;
            this.observation = observation;
        }

        Money estimate() {
            return global.estimate();
        }

        void usage(long in, long out, long usdMicros) {
            observation.highCardinalityKeyValue("tokens.in", Long.toString(in));
            observation.highCardinalityKeyValue("tokens.out", Long.toString(out));
            observation.highCardinalityKeyValue("saiman.cost.usd_micros", Long.toString(usdMicros));
        }

        void stop(String outcome) {
            if (observed.compareAndSet(false, true)) {
                observation.lowCardinalityKeyValue("outcome", outcome);
                observation.stop();
            }
        }
    }

    private final RouteCosting costing;
    private final String tier;
    private final CostGuard guard;
    private final RouterMetrics metrics;
    private final ScopePolicy scopePolicy;
    private final ObservationRegistry observations;

    CostAdvisor(RouteCosting costing, CostGuard guard, RouterMetrics metrics) {
        this(costing, guard, metrics, ScopePolicy.NONE, ObservationRegistry.NOOP);
    }

    CostAdvisor(
            RouteCosting costing,
            CostGuard guard,
            RouterMetrics metrics,
            ScopePolicy scopePolicy,
            ObservationRegistry observations) {
        this.costing = costing;
        this.tier = costing.label();
        this.guard = guard;
        this.metrics = metrics;
        this.scopePolicy = scopePolicy;
        this.observations = observations;
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
        Held reservation = reserve(request);
        ChatClientResponse response;
        try {
            response = chain.nextCall(request);
        } catch (RuntimeException e) {
            afterFailure(reservation, e);
            finishCall(reservation, "error", e);
            throw e;
        }
        settle(reservation, response.chatResponse());
        finishCall(reservation, "ok", null);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            Held reservation = reserve(request);
            AtomicReference<@Nullable ChatResponse> last = new AtomicReference<>();
            AtomicBoolean notSent = new AtomicBoolean();
            Flux<ChatClientResponse> upstream;
            try {
                upstream = chain.nextStream(request);
            } catch (RuntimeException e) {
                afterFailure(reservation, e);
                finishCall(reservation, "error", e);
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
                    reservation.stop("error");
                    return;
                }
                settle(reservation, last.get());
                if (signal == SignalType.ON_COMPLETE) {
                    finishCall(reservation, "ok", null);
                } else if (signal == SignalType.CANCEL) {
                    finishCall(reservation, "cancelled", null);
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
                        finishCall(reservation, "error", e);
                        finish.accept(SignalType.ON_ERROR);
                    })
                    .doOnCancel(() -> finish.accept(SignalType.CANCEL))
                    .doFinally(finish);
        });
    }

    private Held reserve(ChatClientRequest request) {
        Observation observation = Observation.createNotStarted(OBSERVATION, observations)
                .lowCardinalityKeyValue("tier", tier)
                .lowCardinalityKeyValue("model", costing.model());
        try {
            String scope = scopeOf(request);
            if (scope != null) {
                observation.highCardinalityKeyValue("saiman.cost.scope", scope);
            }
            observation.start();
            Money estimate = costing.estimate(request.prompt());
            ScopedCostGuard.ScopeReservation scoped = null;
            ScopedCostGuard scopedGuard = scopePolicy.guard();
            if (scope != null && scopedGuard != null) {
                scoped = scopedGuard.reserve(scope, estimate, budgetOf(request));
            }
            try {
                return new Held(guard.reserve(estimate), scoped, scope, observation);
            } catch (RuntimeException e) {
                if (scoped != null && scope != null) {
                    releaseScope(scopedGuard, scope, scoped);
                }
                throw e;
            }
        } catch (DailyCapExceededException e) {
            refused(observation, "cap", e);
            throw e;
        } catch (ScopeBudgetExceededException e) {
            refused(observation, "scope_budget", e);
            throw e;
        } catch (RequestNotSentException e) {
            refused(observation, "no_scope", e);
            throw e;
        } catch (RuntimeException e) {
            refused(observation, "error", e);
            throw e;
        }
    }

    private void refused(Observation observation, String outcome, RuntimeException e) {
        metrics.call(tier, outcome);
        observation.error(e);
        observation.lowCardinalityKeyValue("outcome", outcome);
        observation.stop();
    }

    /** The scope id of the request, or {@code null} when it has none and none is required. */
    private @Nullable String scopeOf(ChatClientRequest request) {
        Object value = request.context().get(RouterAdvisorParams.COST_SCOPE);
        if (value == null) {
            if (scopePolicy.required()) {
                throw new RequestNotSentException(
                        "this router requires a cost scope (advisor param " + RouterAdvisorParams.COST_SCOPE + ")");
            }
            return null;
        }
        if (!(value instanceof String scope)) {
            throw new IllegalArgumentException("cost scope id must be a string");
        }
        ScopedCostGuard.requireValidScopeId(scope);
        if (scopePolicy.guard() == null) {
            // a scope the router cannot enforce must not be silently ignored
            throw new RequestNotSentException("a cost scope was given but the router has no ScopedCostGuard");
        }
        return scope;
    }

    /** The caller's scope budget, never above the configured maximum; the maximum if none was given. */
    private Money budgetOf(ChatClientRequest request) {
        long max = scopePolicy.maxBudgetUsdMicros();
        Object value = request.context().get(RouterAdvisorParams.COST_SCOPE_BUDGET_USD_MICROS);
        if (value == null) {
            return Money.usdMicros(max);
        }
        if (!(value instanceof Number number) || number.longValue() <= 0) {
            throw new IllegalArgumentException("cost scope budget must be a positive number of USD micros");
        }
        return Money.usdMicros(Math.min(number.longValue(), max));
    }

    /** Counts the call and ends its observation. */
    private void finishCall(Held held, String outcome, @Nullable Throwable failure) {
        metrics.call(tier, outcome);
        if (failure != null) {
            held.observation.error(failure);
        }
        held.stop(outcome);
    }

    private void afterFailure(Held reservation, RuntimeException failure) {
        if (failure instanceof RequestNotSentException) {
            release(reservation);
        }
        // otherwise the outcome is unknown: the reservation stays as the charge
    }

    private void release(Held held) {
        try {
            guard.release(held.global);
        } catch (RuntimeException e) {
            log.error(
                    "Releasing a cost reservation on {} failed: {}",
                    tier,
                    e.getClass().getName());
        }
        if (held.scoped != null && held.scope != null) {
            releaseScope(scopePolicy.guard(), held.scope, held.scoped);
        }
    }

    private void releaseScope(
            @Nullable ScopedCostGuard scoped, String scope, ScopedCostGuard.ScopeReservation reservation) {
        if (scoped == null) {
            return;
        }
        try {
            scoped.release(scope, reservation);
        } catch (RuntimeException e) {
            log.error(
                    "Releasing a scope cost reservation on {} failed: {}",
                    tier,
                    e.getClass().getName());
        }
    }

    /** Settles to the actual cost, or keeps the estimate when the usage is missing or unusable. */
    private void settle(Held reservation, @Nullable ChatResponse response) {
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
        settleGuards(reservation, actual);
        try {
            reservation.usage(in, out, actual.atomicUnits());
            metrics.usage(tier, in, out, actual.atomicUnits());
        } catch (RuntimeException e) {
            log.error(
                    "Recording model metrics on {} failed: {}",
                    tier,
                    e.getClass().getName());
        }
    }

    private void settleGuards(Held held, Money actual) {
        try {
            guard.settle(held.global, actual);
        } catch (RuntimeException e) {
            log.error("Settling model cost on {} failed: {}", tier, e.getClass().getName());
        }
        ScopedCostGuard scopedGuard = scopePolicy.guard();
        if (held.scoped != null && held.scope != null && scopedGuard != null) {
            try {
                scopedGuard.settle(held.scope, held.scoped, actual);
            } catch (RuntimeException e) {
                log.error(
                        "Settling scope cost on {} failed: {}",
                        tier,
                        e.getClass().getName());
            }
        }
    }

    private void keepEstimate(Held reservation, String reason) {
        log.warn("Model call on {} {}; the worst-case estimate is charged instead", tier, reason);
        try {
            metrics.call(tier, "no_usage");
            reservation.usage(0, 0, reservation.estimate().atomicUnits());
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
