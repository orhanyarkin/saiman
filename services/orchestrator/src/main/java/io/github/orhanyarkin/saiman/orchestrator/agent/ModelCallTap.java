package io.github.orhanyarkin.saiman.orchestrator.agent;

import io.github.orhanyarkin.saiman.modelrouter.ModelCallObservation;
import io.github.orhanyarkin.saiman.orchestrator.run.ModelCallRecorder;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.AgentStep;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns the model router's own {@code saiman.model.call} observations into a run's {@code
 * MODEL_CALL_COMPLETED} events and LLM cost (ADR-0011 amendment, ADR-0014). The router prices every
 * round trip (including each iteration of a tool-calling loop) from the reported usage and tags the
 * observation with the cost, the tokens and the cost scope; this handler only copies those numbers,
 * so there is one source of truth and no second price table.
 *
 * <p>Boot registers every {@link ObservationHandler} bean with the application's {@code
 * ObservationRegistry}, the same registry the router's auto-configuration hands to its advisors. The
 * router stops the observation synchronously on the calling thread, so a subscription can carry the
 * pipeline's current step.
 *
 * <p>A round trip without a cost attribute (refused by a cap before sending, or failed before an
 * answer arrived) is not recorded. A recording failure never breaks the model call (the provider has
 * already answered); the subscription is marked failed and the pipeline ends the run.
 */
@Component
public class ModelCallTap implements ObservationHandler<Observation.Context> {

    /** The router's observation name and attribute keys (libs/model-router {@code CostAdvisor}). */
    static final String OBSERVATION = ModelCallObservation.NAME;

    static final String SCOPE = ModelCallObservation.COST_SCOPE;
    static final String COST = ModelCallObservation.COST_USD_MICROS;
    static final String TOKENS_IN = ModelCallObservation.TOKENS_IN;
    static final String TOKENS_OUT = ModelCallObservation.TOKENS_OUT;

    private static final Logger LOG = LoggerFactory.getLogger(ModelCallTap.class);

    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /** Routes the model calls of cost scope {@code scopeId} to {@code recorder} until closed. */
    public Subscription subscribe(String scopeId, ModelCallRecorder recorder) {
        Subscription subscription = new Subscription(scopeId, recorder);
        if (subscriptions.putIfAbsent(scopeId, subscription) != null) {
            throw new IllegalStateException("the cost scope already has a subscription");
        }
        return subscription;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return OBSERVATION.equals(context.getName());
    }

    @Override
    public void onStop(Observation.Context context) {
        String scope = value(context.getHighCardinalityKeyValue(SCOPE));
        Subscription subscription = scope == null ? null : subscriptions.get(scope);
        if (subscription == null) {
            return;
        }
        String cost = value(context.getHighCardinalityKeyValue(COST));
        if (cost == null) {
            return;
        }
        try {
            subscription.recorder.record(new RunEventData.ModelCallCompleted(
                    subscription.step,
                    orUnknown(value(context.getLowCardinalityKeyValue("tier"))),
                    orUnknown(value(context.getLowCardinalityKeyValue("model"))),
                    number(value(context.getHighCardinalityKeyValue(TOKENS_IN))),
                    number(value(context.getHighCardinalityKeyValue(TOKENS_OUT))),
                    Money.usdMicros(Long.parseLong(cost))));
        } catch (RuntimeException e) {
            subscription.failed = true;
            LOG.error(
                    "Recording a model call of scope {} failed ({})",
                    scope,
                    e.getClass().getSimpleName());
        }
    }

    private static @Nullable String value(@Nullable KeyValue keyValue) {
        return keyValue == null ? null : keyValue.getValue();
    }

    private static String orUnknown(@Nullable String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static long number(@Nullable String value) {
        return value == null ? 0 : Long.parseLong(value);
    }

    /** One run's subscription: which step is running and whether a recording failed. */
    public final class Subscription implements AutoCloseable {
        private final String scopeId;
        private final ModelCallRecorder recorder;
        private volatile AgentStep step = AgentStep.PLANNER;
        private volatile boolean failed;

        private Subscription(String scopeId, ModelCallRecorder recorder) {
            this.scopeId = scopeId;
            this.recorder = recorder;
        }

        /** Attributes the following model calls to {@code step}. */
        public void step(AgentStep step) {
            this.step = step;
        }

        /** True once a model call could not be recorded (its cost is then missing from the run). */
        public boolean failed() {
            return failed;
        }

        @Override
        public void close() {
            subscriptions.remove(scopeId, this);
        }
    }
}
