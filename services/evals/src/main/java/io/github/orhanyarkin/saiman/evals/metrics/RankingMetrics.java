package io.github.orhanyarkin.saiman.evals.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Pure ranking metrics over a ranking of disclosure indexes (best first, no duplicates; see
 * {@link Disclosures#dedupe}). Rank positions are 1-based; every metric looks only at the first {@code k}.
 */
public final class RankingMetrics {

    private RankingMetrics() {}

    /** Share of the relevant disclosures found in the first {@code k}: {@code |relevant ∩ top-k| / |relevant|}. */
    public static double recallAtK(List<Long> ranking, Map<Long, Integer> relevant, int k) {
        if (relevant.isEmpty()) {
            throw new IllegalArgumentException("relevant must not be empty");
        }
        long found = top(ranking, k).stream().filter(relevant::containsKey).count();
        return (double) found / relevant.size();
    }

    /** {@code 1 / rank} of the first relevant disclosure within the first {@code k}, or 0. */
    public static double reciprocalRankAtK(List<Long> ranking, Map<Long, Integer> relevant, int k) {
        List<Long> top = top(ranking, k);
        for (int i = 0; i < top.size(); i++) {
            if (relevant.containsKey(top.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * Normalised discounted cumulative gain with linear gain: a disclosure at rank {@code r} with grade
     * {@code g} adds {@code g / log2(r + 1)}; the ideal ranking sorts the grades in descending order.
     * Disclosures without a grade count as 0.
     */
    public static double ndcgAtK(List<Long> ranking, Map<Long, Integer> grades, int k) {
        List<Integer> actual = new ArrayList<>();
        for (Long index : top(ranking, k)) {
            actual.add(grades.getOrDefault(index, 0));
        }
        List<Integer> ideal = grades.values().stream()
                .sorted(Comparator.reverseOrder())
                .limit(k)
                .toList();
        double idcg = dcg(ideal);
        return idcg == 0.0 ? 0.0 : dcg(actual) / idcg;
    }

    /**
     * Freshness gains: member {@code j} (1-based, newest first) of the latest-N list is worth {@code N + 1 - j}
     * (for N = 5 that is {@code 6 - rank}: 5, 4, 3, 2, 1). Fed to {@link #ndcgAtK}.
     */
    public static Map<Long, Integer> recencyGrades(List<Long> latest) {
        Map<Long, Integer> grades = new java.util.LinkedHashMap<>();
        for (int i = 0; i < latest.size(); i++) {
            grades.put(latest.get(i), latest.size() - i);
        }
        return grades;
    }

    /** {@code |first-n ∩ latest| / n} with {@code n} = {@code latest.size()} capped at the ranking cut-off. */
    public static double recencyAtN(List<Long> ranking, List<Long> latest, int n) {
        long hits = top(ranking, n).stream().filter(latest::contains).count();
        return (double) hits / n;
    }

    /** 1 if the newest disclosure (first in {@code latest}) is within the first {@code n}, else 0. */
    public static double latestHitAtN(List<Long> ranking, List<Long> latest, int n) {
        return top(ranking, n).contains(latest.get(0)) ? 1.0 : 0.0;
    }

    private static double dcg(List<Integer> gains) {
        double sum = 0.0;
        for (int i = 0; i < gains.size(); i++) {
            sum += gains.get(i) / (Math.log(i + 2.0) / Math.log(2.0));
        }
        return sum;
    }

    private static List<Long> top(List<Long> ranking, int k) {
        return ranking.subList(0, Math.min(k, ranking.size()));
    }
}
