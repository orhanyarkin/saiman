package io.github.orhanyarkin.saiman.ingest.pipeline;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Result of one job run.
 *
 * @param alreadyRunning another run holds the advisory lock; nothing was done
 * @param aborted the run stopped early (circuit open); it resumes from the cursors next time
 */
public record RunReport(
        boolean alreadyRunning,
        boolean aborted,
        Map<Outcome, Integer> outcomes,
        List<String> unknownTickers,
        int tickersDone) {

    public RunReport {
        outcomes = Map.copyOf(outcomes);
        unknownTickers = List.copyOf(unknownTickers);
    }

    public static RunReport busy() {
        return new RunReport(true, false, Map.of(), List.of(), 0);
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
