package io.github.orhanyarkin.saiman.ingest.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import io.github.orhanyarkin.saiman.ingest.retrieval.RrfFusion.Fused;
import java.util.List;
import org.junit.jupiter.api.Test;

class RrfFusionTests {

    @Test
    void itemInBothLegsBeatsItemsInOne() {
        List<Fused> result = RrfFusion.fuse(List.of("a", "b", "c"), List.of("c", "d"), 60, 10);

        assertThat(result).extracting(Fused::id).containsExactly("c", "a", "b", "d");
        Fused c = result.get(0);
        assertThat(c.score()).isCloseTo(1.0 / 63 + 1.0 / 61, within(1e-12));
        assertThat(c.vectorRank()).isEqualTo(3);
        assertThat(c.lexicalRank()).isEqualTo(1);
    }

    @Test
    void singleLegHitsKeepTheirRankAndLeaveTheOtherNull() {
        List<Fused> result = RrfFusion.fuse(List.of("a", "b"), List.of(), 60, 10);

        assertThat(result).extracting(Fused::id).containsExactly("a", "b");
        assertThat(result.get(0).lexicalRank()).isNull();
        assertThat(result.get(0).score()).isCloseTo(1.0 / 61, within(1e-12));
    }

    @Test
    void tiesBreakOnBestRankThenId() {
        // "x" is vector rank 1 only, "y" is lexical rank 1 only: equal score and equal best rank -> id order
        List<Fused> result = RrfFusion.fuse(List.of("y"), List.of("x"), 60, 10);
        assertThat(result).extracting(Fused::id).containsExactly("x", "y");
    }

    @Test
    void kControlsHowMuchTopRanksDominate() {
        List<String> vector = List.of("a", "b");
        List<String> lexical = List.of("b", "c");
        // With a tiny k the top rank of a single leg beats a two-leg hit at ranks 2 and 1... check both regimes.
        assertThat(RrfFusion.fuse(vector, lexical, 1, 3).get(0).id()).isEqualTo("b");
        assertThat(RrfFusion.fuse(List.of("a", "z", "y", "x", "b"), List.of("q", "w", "v", "u", "t", "b"), 1, 1)
                        .get(0)
                        .id())
                .isEqualTo("a");
        assertThat(RrfFusion.fuse(List.of("a", "z", "y", "x", "b"), List.of("q", "w", "v", "u", "t", "b"), 1000, 1)
                        .get(0)
                        .id())
                .isEqualTo("b");
    }

    @Test
    void limitAppliesAfterFusionAndDuplicatesInALegCountOnce() {
        List<Fused> result = RrfFusion.fuse(List.of("a", "a", "b"), List.of(), 60, 1);
        assertThat(result).hasSize(1);
        assertThat(RrfFusion.fuse(List.of("a", "a", "b"), List.of(), 60, 5)
                        .get(1)
                        .vectorRank())
                .isEqualTo(2);
    }

    @Test
    void emptyRecencyLegGivesExactlyTheTwoLegResult() {
        List<String> vector = List.of("a", "b", "c");
        List<String> lexical = List.of("c", "d");

        assertThat(RrfFusion.fuse(vector, lexical, List.of(), 60, 10))
                .isEqualTo(RrfFusion.fuse(vector, lexical, 60, 10));
        // and the scores are the same doubles as before the third leg existed
        assertThat(RrfFusion.fuse(vector, lexical, List.of(), 60, 10).get(0).score())
                .isEqualTo(1.0 / 63 + 1.0 / 61);
    }

    @Test
    void recencyLegAddsATermAndCanSurfaceANewestDocument() {
        // "n" is only in the recency leg (rank 1); "a" is vector rank 1 only; "c" is in all three legs.
        List<Fused> result = RrfFusion.fuse(List.of("a", "c"), List.of("c"), List.of("n", "c"), 60, 10);

        assertThat(result).extracting(Fused::id).containsExactly("c", "a", "n");
        Fused c = result.get(0);
        assertThat(c.score()).isCloseTo(1.0 / 62 + 1.0 / 61 + 1.0 / 62, within(1e-12));
        assertThat(c.recencyRank()).isEqualTo(2);
        Fused n = result.get(2);
        assertThat(n.score()).isCloseTo(1.0 / 61, within(1e-12));
        assertThat(n.vectorRank()).isNull();
        assertThat(n.lexicalRank()).isNull();
        assertThat(n.recencyRank()).isEqualTo(1);
    }

    @Test
    void recencyWeightScalesOnlyTheRecencyTerm() {
        // "n" is recency rank 1 only: weight 3 gives 3/61, more than vector rank 1 (1/61) or a two-leg hit at 5 and 5
        List<String> vector = List.of("a", "x", "y", "z", "c");
        List<String> lexical = List.of("p", "q", "r", "s", "c");

        List<Fused> weighted = RrfFusion.fuse(vector, lexical, List.of("n"), 3.0, 60, 10);

        assertThat(weighted.get(0).id()).isEqualTo("n");
        assertThat(weighted.get(0).score()).isCloseTo(3.0 / 61, within(1e-12));
        Fused c = weighted.stream().filter(f -> f.id().equals("c")).findFirst().orElseThrow();
        assertThat(c.score()).isCloseTo(2.0 / 65, within(1e-12)); // untouched by the weight
        // weight 1 (the 5-argument overload) leaves "a" first
        assertThat(RrfFusion.fuse(vector, lexical, List.of("n"), 60, 10).get(0).id())
                .isNotEqualTo("n");
    }

    @Test
    void recencyOnlyItemsAreDeduplicated() {
        List<Fused> result = RrfFusion.fuse(List.of(), List.of(), List.of("z", "z", "y"), 60, 10);
        assertThat(result).extracting(Fused::id).containsExactly("z", "y");
    }

    @Test
    void emptyLegsGiveEmptyResultAndBadKIsRejected() {
        assertThat(RrfFusion.fuse(List.of(), List.of(), 60, 5)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> RrfFusion.fuse(List.of("a"), List.of(), 0, 5));
    }
}
