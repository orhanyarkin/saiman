package io.github.orhanyarkin.saiman.evals.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class DisclosuresTests {

    @Test
    void chunksOfOneDisclosureCollapseToTheirBestRank() {
        List<String> chunks = List.of("kap:1118495:0002", "kap:1100000:0000", "kap:1118495:0000", "kap:1093500:0001");

        assertThat(Disclosures.dedupe(chunks)).containsExactly(1118495L, 1100000L, 1093500L);
    }

    @Test
    void emptyStaysEmpty() {
        assertThat(Disclosures.dedupe(List.of())).isEmpty();
    }

    @Test
    void foreignIdsAreRejectedNotSilentlyDropped() {
        assertThatIllegalArgumentException().isThrownBy(() -> Disclosures.indexOf("doc-7"));
        assertThatIllegalArgumentException().isThrownBy(() -> Disclosures.indexOf("kap:12:1"));
    }
}
