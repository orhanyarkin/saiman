package io.github.orhanyarkin.saiman.sellerapi.llm;

import io.github.orhanyarkin.saiman.modelrouter.ModelCallObservation;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sums the model router's own cost observations ({@code saiman.model.call}, tag {@link
 * ModelCallObservation#COST_USD_MICROS}) for the code running inside {@link #measure()} on the current thread, so a
 * caller can report what one request cost without a second price table (the eval API's {@code modelCostUsdMicros}).
 *
 * <p>Boot registers every {@link ObservationHandler} bean with the application's {@code ObservationRegistry}, the
 * registry the router's auto-configuration hands to its cost advisor. The router stops the observation synchronously
 * on the calling thread, so a thread-bound measurement sees exactly the calls of its own request. A round trip the
 * router refused before sending (daily cap) carries no cost and adds nothing.
 */
@Component
public class ModelCostMeter implements ObservationHandler<Observation.Context> {

    private static final Logger log = LoggerFactory.getLogger(ModelCostMeter.class);

    private static final ThreadLocal<@Nullable Measurement> CURRENT = new ThreadLocal<>();

    /**
     * Starts measuring the current thread's model calls; close the result (try-with-resources) to stop.
     *
     * @throws IllegalStateException if a measurement is already open on this thread
     */
    public Measurement measure() {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("a model cost measurement is already open on this thread");
        }
        Measurement measurement = new Measurement();
        CURRENT.set(measurement);
        return measurement;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return ModelCallObservation.NAME.equals(context.getName());
    }

    @Override
    public void onStop(Observation.Context context) {
        Measurement measurement = CURRENT.get();
        if (measurement == null) {
            return;
        }
        KeyValue cost = context.getHighCardinalityKeyValue(ModelCallObservation.COST_USD_MICROS);
        if (cost == null) {
            return;
        }
        try {
            measurement.add(Long.parseLong(cost.getValue()));
        } catch (NumberFormatException e) {
            log.warn("model call cost tag is not an integer; not counted");
        }
    }

    /** One open measurement: the sum of the router's cost tags in USD micro-dollars. */
    public static final class Measurement implements AutoCloseable {

        private long usdMicros;

        private Measurement() {}

        private void add(long micros) {
            if (micros > 0) {
                usdMicros = Math.addExact(usdMicros, micros);
            }
        }

        /** The cost so far, USD micro-dollars. */
        public long usdMicros() {
            return usdMicros;
        }

        @Override
        public void close() {
            if (CURRENT.get() == this) {
                CURRENT.remove();
            }
        }
    }
}
