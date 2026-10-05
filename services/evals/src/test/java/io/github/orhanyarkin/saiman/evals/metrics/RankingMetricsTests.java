package io.github.orhanyarkin.saiman.evals.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every expected value below was computed by hand (see the comments), not copied from the code. */
class RankingMetricsTests {

    private static final double EPS = 1e-9;

    // relevant: 1 (grade 3), 2 (grade 2), 3 (grade 2); the ranking finds 1 at rank 2 and 2 at rank 4
    private static final Map<Long, Integer> GRADES = graded(1, 3, 2, 2, 3, 2);
    private static final List<Long> RANKING = List.of(9L, 1L, 8L, 2L);

    @Test
    void recallIsTheShareOfRelevantDisclosuresInTheTopK() {
        assertThat(RankingMetrics.recallAtK(RANKING, GRADES, 10)).isCloseTo(2.0 / 3.0, within(EPS));
        assertThat(RankingMetrics.recallAtK(RANKING, GRADES, 2)).isCloseTo(1.0 / 3.0, within(EPS));
        assertThat(RankingMetrics.recallAtK(RANKING, GRADES, 1)).isZero();
    }

    @Test
    void recallRejectsAnItemWithoutLabels() {
        assertThatIllegalArgumentException().isThrownBy(() -> RankingMetrics.recallAtK(RANKING, Map.of(), 10));
    }

    @Test
    void reciprocalRankIsOneOverTheRankOfTheFirstRelevantDisclosure() {
        assertThat(RankingMetrics.reciprocalRankAtK(RANKING, GRADES, 10)).isEqualTo(0.5);
        assertThat(RankingMetrics.reciprocalRankAtK(List.of(1L), GRADES, 10)).isEqualTo(1.0);
        assertThat(RankingMetrics.reciprocalRankAtK(RANKING, GRADES, 1)).isZero(); // cut off before rank 2
        assertThat(RankingMetrics.reciprocalRankAtK(List.of(7L, 8L), GRADES, 10))
                .isZero();
        assertThat(RankingMetrics.reciprocalRankAtK(List.of(), GRADES, 10)).isZero();
    }

    @Test
    void ndcgUsesLinearGainsAndALogarithmicDiscount() {
        // DCG  = 3/log2(2+1) + 2/log2(4+1)        = 1.8928 + 0.8614 = 2.754142...
        // IDCG = 3/log2(2) + 2/log2(3) + 2/log2(4) = 3 + 1.2619 + 1   = 5.261860...
        assertThat(RankingMetrics.ndcgAtK(RANKING, GRADES, 10)).isCloseTo(2.7541423769 / 5.2618595071, within(EPS));
        assertThat(RankingMetrics.ndcgAtK(RANKING, GRADES, 10)).isCloseTo(0.523416175, within(1e-8));
    }

    @Test
    void aPerfectRankingScoresOneAndAnEmptyOneZero() {
        assertThat(RankingMetrics.ndcgAtK(List.of(1L, 2L, 3L), GRADES, 10)).isCloseTo(1.0, within(EPS));
        assertThat(RankingMetrics.ndcgAtK(List.of(), GRADES, 10)).isZero();
        assertThat(RankingMetrics.ndcgAtK(List.of(5L, 6L), GRADES, 10)).isZero();
    }

    @Test
    void ndcgOnlyLooksAtTheFirstKAndTheIdealIsCutToo() {
        // k=1: DCG = 0 (rank 1 is not relevant) -> 0; with the relevant one first: 3/3 = 1
        assertThat(RankingMetrics.ndcgAtK(RANKING, GRADES, 1)).isZero();
        assertThat(RankingMetrics.ndcgAtK(List.of(1L, 9L), GRADES, 1)).isCloseTo(1.0, within(EPS));
    }

    // --- freshness: latest = 11 (newest) .. 15 (5th newest) -------------------------------------

    private static final List<Long> LATEST = List.of(11L, 12L, 13L, 14L, 15L);
    private static final List<Long> FRESH_RANKING = List.of(12L, 99L, 11L, 15L, 98L, 13L);

    @Test
    void recencyGradesAreSixMinusRank() {
        assertThat(RankingMetrics.recencyGrades(LATEST))
                .containsExactly(
                        Map.entry(11L, 5), Map.entry(12L, 4), Map.entry(13L, 3), Map.entry(14L, 2), Map.entry(15L, 1));
    }

    @Test
    void recencyAtFiveCountsMembersOfTheLatestListInTheTopFive() {
        // top 5 = 12, 99, 11, 15, 98 -> members 12, 11, 15 -> 3/5; 13 is at rank 6 and does not count
        assertThat(RankingMetrics.recencyAtN(FRESH_RANKING, LATEST, 5)).isCloseTo(0.6, within(EPS));
        assertThat(RankingMetrics.recencyAtN(List.of(11L, 12L, 13L, 14L, 15L), LATEST, 5))
                .isEqualTo(1.0);
        assertThat(RankingMetrics.recencyAtN(List.of(1L, 2L), LATEST, 5)).isZero();
    }

    @Test
    void latestHitIsOneOnlyWhenTheNewestDisclosureIsInTheTopN() {
        assertThat(RankingMetrics.latestHitAtN(FRESH_RANKING, LATEST, 5)).isEqualTo(1.0);
        assertThat(RankingMetrics.latestHitAtN(FRESH_RANKING, LATEST, 2)).isZero();
        assertThat(RankingMetrics.latestHitAtN(List.of(12L, 13L), LATEST, 5)).isZero();
    }

    @Test
    void freshnessNdcgUsesRecencyGains() {
        // gains by rank: 4, 0, 5, 1, 0, 3 -> DCG = 4/1 + 0 + 5/2 + 1/log2(5) + 0 + 3/log2(7) = 7.99930
        // ideal 5, 4, 3, 2, 1 -> 5 + 4/log2(3) + 3/2 + 2/log2(5) + 1/log2(6) = 10.27192
        assertThat(RankingMetrics.ndcgAtK(FRESH_RANKING, RankingMetrics.recencyGrades(LATEST), 10))
                .isCloseTo(0.77875359, within(1e-7));
        assertThat(RankingMetrics.ndcgAtK(LATEST, RankingMetrics.recencyGrades(LATEST), 10))
                .isCloseTo(1.0, within(EPS));
    }

    private static Map<Long, Integer> graded(long a, int ga, long b, int gb, long c, int gc) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        map.put(a, ga);
        map.put(b, gb);
        map.put(c, gc);
        return map;
    }
}
