package io.github.orhanyarkin.saiman.evals.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class PercentileTests {

    @Test
    void nearestRankPicksTheSmallestValueCoveringThePercentile() {
        // 20 values 1..20: p95 -> rank ceil(0.95 * 20) = 19 -> 19; p50 -> rank 10 -> 10; p100 -> 20
        List<Long> values =
                java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList();
        assertThat(Percentile.nearestRank(values, 95)).isEqualTo(19);
        assertThat(Percentile.nearestRank(values, 50)).isEqualTo(10);
        assertThat(Percentile.nearestRank(values, 100)).isEqualTo(20);
    }

    @Test
    void unsortedInputAndSingleValueWork() {
        assertThat(Percentile.nearestRank(List.of(30L, 10L, 20L), 95)).isEqualTo(30);
        assertThat(Percentile.nearestRank(List.of(7L), 95)).isEqualTo(7);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> Percentile.nearestRank(List.of(), 95));
        assertThatIllegalArgumentException().isThrownBy(() -> Percentile.nearestRank(List.of(1L), 0));
    }
}
