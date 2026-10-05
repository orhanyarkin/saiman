package io.github.orhanyarkin.saiman.evals.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoldenSetLoaderTests {

    private static final String VALID = """
            version: 1
            corpus:
              watermark: 2023-12-29T20:46:52Z
              corpusVersion: "24303757d747de77"
            items:
              - id: R-ONE
                kind: RETRIEVAL
                ticker: THYAO
                question: "THYAO pay geri alım bildirimi"
                expected:
                  relevant:
                    - {index: 1207592, grade: 3}
                    - {index: 1000001, grade: 2}
              - id: F-ONE
                kind: FRESHNESS
                ticker: THYAO
                question: "THYAO son özel durum açıklamaları"
                expected:
                  latest: [5, 4, 3, 2, 1]
              - id: A-ONE
                kind: ANSWER
                ticker: THYAO
                question: "Ne zaman?"
                expected:
                  sources: [1207592]
                  requiredFacts:
                    - anyOf: ["18.10.2023", "2023-10-18"]
              - id: U-ONE
                kind: UNANSWERABLE
                ticker: AKBNK
                question: "Net kar?"
                expected:
                  reason: NOT_IN_CORPUS
            """;

    @Test
    void theShippedGoldenSetIsValidAndHasTheAgreedShape() {
        GoldenSet set = GoldenSetLoader.load("classpath:golden/golden-set.v1.yaml");

        assertThat(set.version()).isEqualTo(1);
        assertThat(set.corpus().corpusVersion()).isNotBlank();
        assertThat(set.corpus().watermark()).isEqualTo(Instant.parse("2023-12-29T20:46:52Z"));
        assertThat(set.items(Kind.RETRIEVAL)).hasSize(16); // one per ticker that has indexed disclosures
        assertThat(set.items(Kind.FRESHNESS)).hasSize(3);
        assertThat(set.items(Kind.ANSWER)).hasSize(5);
        assertThat(set.items(Kind.UNANSWERABLE)).hasSize(2);
        assertThat(set.items(Kind.TEMPORAL))
                .extracting(GoldenSet.GoldenItem::question)
                .containsExactly("SISE güncel bildirimlerinde neler var?", "KCHOL en son açıklamaları neler?");
        // the answer service needs >= 2 valid citations, so a single-source question can only fail
        assertThat(set.items(Kind.ANSWER)).allSatisfy(item -> {
            assertThat(item.expected().sources()).hasSize(2).doesNotHaveDuplicates();
            assertThat(item.expected().requiredFacts()).hasSize(2);
        });
        assertThat(set.items(Kind.ANSWER))
                .extracting(GoldenSet.GoldenItem::ticker)
                .doesNotHaveDuplicates();
        assertThat(set.items(Kind.FRESHNESS))
                .extracting(GoldenSet.GoldenItem::question)
                .contains("THYAO son özel durum açıklamaları");
        assertThat(set.items(Kind.RETRIEVAL))
                .extracting(GoldenSet.GoldenItem::ticker)
                .doesNotHaveDuplicates();
        assertThat(set.items()).extracting(GoldenSet.GoldenItem::id).doesNotHaveDuplicates();
    }

    @Test
    void aValidFileLoadsEveryKindWithItsExpectedFields() {
        GoldenSet set = GoldenSetLoader.parse(VALID);

        assertThat(set.items()).hasSize(4);
        GoldenSet.Expected retrieval = set.items().get(0).expected();
        assertThat(retrieval.relevant()).containsExactly(Map.entry(1207592L, 3), Map.entry(1000001L, 2));
        assertThat(set.items().get(1).expected().latest()).containsExactly(5L, 4L, 3L, 2L, 1L);
        assertThat(set.items().get(2).expected().requiredFacts())
                .containsExactly(java.util.List.of("18.10.2023", "2023-10-18"));
        assertThat(set.items().get(3).expected().reason()).isEqualTo("NOT_IN_CORPUS");
    }

    @Test
    void problemsAreCollectedWithTheirLocation() {
        String broken = VALID.replace("kind: ANSWER", "kind: ANSWERED")
                .replace("grade: 2", "grade: 7")
                .replace("latest: [5, 4, 3, 2, 1]", "latest: [5, 4, 3]")
                .replace("ticker: AKBNK", "ticker: akbnk");

        assertThatThrownBy(() -> GoldenSetLoader.parse(broken))
                .isInstanceOf(GoldenSetException.class)
                .hasMessageContaining("items[2] (A-ONE).kind: must be one of")
                .hasMessageContaining("items[1] (F-ONE).expected.latest: must list 5 distinct")
                .hasMessageContaining("items[3] (U-ONE).ticker: must match")
                .hasMessageContaining("grade: must be 1, 2 or 3");
    }

    @Test
    void unknownKeysAndKindMismatchesAreErrors() {
        String typo = VALID.replace("requiredFacts:", "requiredFact:");
        assertThatThrownBy(() -> GoldenSetLoader.parse(typo))
                .isInstanceOf(GoldenSetException.class)
                .hasMessageContaining("requiredFact: unknown key")
                .hasMessageContaining("requiredFacts: must be a non-empty list");

        String leftover = VALID.replace(
                "expected:\n      reason: NOT_IN_CORPUS", "expected:\n      reason: X\n      latest: [1]");
        assertThatThrownBy(() -> GoldenSetLoader.parse(leftover))
                .isInstanceOf(GoldenSetException.class)
                .hasMessageContaining("latest: not allowed for this kind");
    }

    @Test
    void duplicateIdsVersionAndMissingSectionsAreErrors() {
        assertThatThrownBy(() -> GoldenSetLoader.parse(VALID.replace("id: F-ONE", "id: R-ONE")))
                .hasMessageContaining(".id: duplicate");
        assertThatThrownBy(() -> GoldenSetLoader.parse(VALID.replace("version: 1", "version: 2")))
                .hasMessageContaining("version: must be the integer 1");
        assertThatThrownBy(() -> GoldenSetLoader.parse("version: 1\n"))
                .isInstanceOf(GoldenSetException.class)
                .hasMessageContaining("corpus")
                .hasMessageContaining("items: must be a non-empty list");
        assertThatThrownBy(() -> GoldenSetLoader.parse("- just\n- a list\n")).isInstanceOf(GoldenSetException.class);
    }

    @Test
    void invalidYamlAndMissingFilesGiveClearErrors() {
        assertThatThrownBy(() -> GoldenSetLoader.parse("items: [unclosed"))
                .isInstanceOf(GoldenSetException.class)
                .hasMessageContaining("not valid YAML");
        assertThatThrownBy(() -> GoldenSetLoader.load("file:/definitely/not/here.yaml"))
                .isInstanceOf(GoldenSetException.class)
                .hasMessageContaining("cannot read golden set");
    }

    @Test
    void duplicateYamlKeysAreRejected() {
        assertThatThrownBy(() -> GoldenSetLoader.parse(VALID + "version: 1\n"))
                .isInstanceOf(GoldenSetException.class)
                .hasMessageContaining("not valid YAML");
    }
}
