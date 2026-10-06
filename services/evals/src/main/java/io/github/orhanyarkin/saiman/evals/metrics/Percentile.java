package io.github.orhanyarkin.saiman.evals.metrics;

import java.util.List;

/** Nearest-rank percentile: the smallest value with at least {@code p} percent of the values at or below it. */
public final class Percentile {

    private Percentile() {}

    public static long nearestRank(List<Long> values, double percent) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("no values");
        }
        if (percent <= 0 || percent > 100) {
            throw new IllegalArgumentException("percent must be in (0, 100]");
        }
        List<Long> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
        return sorted.get(Math.max(1, rank) - 1);
    }
}
