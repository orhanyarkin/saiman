package io.github.orhanyarkin.saiman.ingest.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reciprocal rank fusion (Cormack et al., 2009): each leg contributes {@code 1 / (k + rank)} for
 * every item it ranked, ranks are 1-based, and an item found by both legs sums both terms. It
 * needs no score normalisation, which is why it suits mixing cosine distance with {@code
 * ts_rank_cd}. Pure and deterministic: ties break on the better single-leg rank, then on id.
 */
public final class RrfFusion {

    public static final int DEFAULT_K = 60;

    private RrfFusion() {}

    /**
     * @param vectorRank 1-based rank in the vector leg, or null
     * @param lexicalRank 1-based rank in the lexical leg, or null
     * @param recencyRank 1-based rank in the optional recency leg, or null; it only feeds the score (the shared
     *     {@code RetrievedChunk} contract has no field for it)
     */
    public record Fused(
            String id,
            double score,
            @Nullable Integer vectorRank,
            @Nullable Integer lexicalRank,
            @Nullable Integer recencyRank) {

        int bestRank() {
            int v = vectorRank == null ? Integer.MAX_VALUE : vectorRank;
            int l = lexicalRank == null ? Integer.MAX_VALUE : lexicalRank;
            int r = recencyRank == null ? Integer.MAX_VALUE : recencyRank;
            return Math.min(Math.min(v, l), r);
        }
    }

    public static List<Fused> fuse(List<String> vectorLeg, List<String> lexicalLeg, int k, int limit) {
        return fuse(vectorLeg, lexicalLeg, List.of(), k, limit);
    }

    /**
     * Three-leg fusion. An empty {@code recencyLeg} contributes nothing, so the result is exactly the
     * two-leg result (same scores, same order).
     */
    public static List<Fused> fuse(
            List<String> vectorLeg, List<String> lexicalLeg, List<String> recencyLeg, int k, int limit) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be positive");
        }
        Map<String, Integer> vector = ranks(vectorLeg);
        Map<String, Integer> lexical = ranks(lexicalLeg);
        Map<String, Integer> recency = ranks(recencyLeg);
        Map<String, Fused> fused = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(vector.keySet());
        lexical.keySet().stream().filter(id -> !vector.containsKey(id)).forEach(ids::add);
        recency.keySet().stream()
                .filter(id -> !vector.containsKey(id) && !lexical.containsKey(id))
                .forEach(ids::add);
        for (String id : ids) {
            Integer v = vector.get(id);
            Integer l = lexical.get(id);
            Integer r = recency.get(id);
            double score = (v == null ? 0.0 : 1.0 / (k + v)) + (l == null ? 0.0 : 1.0 / (k + l));
            if (r != null) {
                score += 1.0 / (k + r);
            }
            fused.put(id, new Fused(id, score, v, l, r));
        }
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(Fused::score)
                        .reversed()
                        .thenComparingInt(Fused::bestRank)
                        .thenComparing(Fused::id))
                .limit(limit)
                .toList();
    }

    /** First occurrence wins; duplicates inside a leg are ignored. */
    private static Map<String, Integer> ranks(List<String> leg) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (String id : leg) {
            ranks.putIfAbsent(id, ranks.size() + 1);
        }
        return ranks;
    }
}
