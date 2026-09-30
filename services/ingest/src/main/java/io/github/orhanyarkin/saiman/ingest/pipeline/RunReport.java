package io.github.orhanyarkin.saiman.ingest.pipeline;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Result of one job run.
 *
 * @param alreadyRunning another run holds the advisory lock; nothing was done
 * @param aborted the run stopped early (circuit open, credential/config error); it resumes from the cursors
 *     next time
 * @param abortReason non-secret reason for the abort (status and MKK error code), or null
 * @param failedTickers tickers whose scan failed unexpectedly; their cursors were kept and the run went on
 */
public record RunReport(
        boolean alreadyRunning,
        boolean aborted,
        @Nullable String abortReason,
        Map<Outcome, Integer> outcomes,
        List<String> unknownTickers,
        List<String> failedTickers,
        int tickersDone) {

    public RunReport {
        outcomes = Map.copyOf(outcomes);
        unknownTickers = List.copyOf(unknownTickers);
        failedTickers = List.copyOf(failedTickers);
    }

    public static RunReport busy() {
        return new RunReport(true, false, null, Map.of(), List.of(), List.of(), 0);
    }

    public int count(Outcome outcome) {
        return outcomes.getOrDefault(outcome, 0);
    }

    /** Mutable tally used while a run is in progress (single-threaded). */
    static final class Tally {
        final Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        int tickersDone;

        void add(Outcome outcome) {
            outcomes.merge(outcome, 1, Integer::sum);
        }
    }
}
