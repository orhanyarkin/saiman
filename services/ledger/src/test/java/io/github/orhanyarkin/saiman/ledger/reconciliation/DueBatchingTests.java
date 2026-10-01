package io.github.orhanyarkin.saiman.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** {@link ReconciliationRepository#fairShare}: the reserved share for reported-tx payments, and spill-over. */
class DueBatchingTests {

    private static List<String> keys(String prefix, int n) {
        return IntStream.range(0, n).mapToObj(i -> prefix + i).toList();
    }

    @Test
    void floodOfRowsWithoutTxLeavesHalfTheBatchToReportedTx() {
        List<String> due = ReconciliationRepository.fairShare(keys("tx", 40), keys("flood", 100), 50);

        assertThat(due).hasSize(50);
        assertThat(due.subList(0, 25)).isEqualTo(keys("tx", 25));
        assertThat(due.subList(25, 50)).isEqualTo(keys("flood", 25));
    }

    @Test
    void unusedShareSpillsOverEitherWay() {
        assertThat(ReconciliationRepository.fairShare(keys("tx", 3), keys("flood", 100), 50))
                .hasSize(50)
                .startsWith("tx0", "tx1", "tx2", "flood0");
        assertThat(ReconciliationRepository.fairShare(keys("tx", 100), keys("flood", 2), 50))
                .hasSize(50)
                .containsSubsequence("tx24", "flood0", "flood1", "tx25")
                .endsWith("tx47");
        assertThat(ReconciliationRepository.fairShare(List.of(), List.of(), 50)).isEmpty();
    }
}
