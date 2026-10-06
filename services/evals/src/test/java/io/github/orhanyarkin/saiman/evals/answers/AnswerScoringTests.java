package io.github.orhanyarkin.saiman.evals.answers;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.shared.eval.EvalOutcome;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Hand-computed cases for the judge-free answer scoring. */
class AnswerScoringTests {

    @Test
    void datesInEverySpellingNormaliseToIso() {
        assertThat(AnswerScoring.normalize("15.11.2023")).isEqualTo("2023-11-15");
        assertThat(AnswerScoring.normalize("5/3/2023")).isEqualTo("2023-03-05");
        assertThat(AnswerScoring.normalize("15 Kasım 2023")).isEqualTo("2023-11-15");
        assertThat(AnswerScoring.normalize("15 KASIM 2023")).isEqualTo("2023-11-15"); // Turkish dotless i
        assertThat(AnswerScoring.normalize("2023-11-15T10:15:00+03:00")).isEqualTo("2023-11-15t10:15:00+03:00");
        assertThat(AnswerScoring.normalize("1 Ağustos 2023")).isEqualTo("2023-08-01");
    }

    @Test
    void percentagesNormaliseToNumberThenSign() {
        assertThat(AnswerScoring.normalize("%12,5")).isEqualTo("12.5%");
        assertThat(AnswerScoring.normalize("yüzde 12,5")).isEqualTo("12.5%");
        assertThat(AnswerScoring.normalize("12,5 %")).isEqualTo("12.5%");
        assertThat(AnswerScoring.normalize("12.5%")).isEqualTo("12.5%");
    }

    @Test
    void aFactMatchesAnyAcceptedSpellingInAnyWrittenForm() {
        List<String> anyOf = List.of("21.03.2023", "21 Mart 2023", "2023-03-21");
        assertThat(AnswerScoring.factMatched("Bildirim 21 Mart 2023 tarihinde yayımlandı.", anyOf))
                .isTrue();
        assertThat(AnswerScoring.factMatched("Yayın tarihi: 21/3/2023.", anyOf)).isTrue();
        assertThat(AnswerScoring.factMatched("published: 2023-03-21T09:30:00+03:00", anyOf))
                .isTrue();
        assertThat(AnswerScoring.factMatched("Bildirim 22 Mart 2023 tarihlidir.", anyOf))
                .isFalse();
    }

    @Test
    void digitsMustNotMatchInsideLongerNumbers() {
        assertThat(AnswerScoring.factMatched("oran 12,5%", List.of("5%"))).isFalse();
        assertThat(AnswerScoring.factMatched("oran 5%", List.of("%5"))).isTrue();
        assertThat(AnswerScoring.factMatched("sayı 1250", List.of("125"))).isFalse();
        assertThat(AnswerScoring.factMatched("oran yüzde 12,5", List.of("12.5%")))
                .isTrue();
    }

    @Test
    void factRecallIsMatchedEntriesOverEntries() {
        List<List<String>> facts = List.of(List.of("18.10.2023"), List.of("geri alım"), List.of("%5"));
        assertThat(AnswerScoring.factRecall("18 Ekim 2023 geri alım bildirimi", facts))
                .isEqualTo(2.0 / 3.0);
        assertThat(AnswerScoring.factRecall("hiçbiri", facts)).isZero();
        assertThat(AnswerScoring.factRecall("x", List.of())).isEqualTo(1.0);
    }

    @Test
    void citationValidityNeedsWellFormedIdAndAKnownDisclosure() {
        Set<Long> allowed = Set.of(100L, 200L);
        assertThat(AnswerScoring.citationValid("kap:100:0003", allowed)).isTrue();
        assertThat(AnswerScoring.citationValid("kap:300:0000", allowed)).isFalse();
        assertThat(AnswerScoring.citationValid("kap:100", allowed)).isFalse();
        assertThat(AnswerScoring.citationValid("doc:100:0000", allowed)).isFalse();
        assertThat(AnswerScoring.validCitations(
                        List.of("kap:100:0000", "kap:200:0001", "bogus", "kap:9:0000"), allowed))
                .isEqualTo(2);
    }

    @Test
    void citationRecallIsTheShareOfExpectedSourcesCited() {
        assertThat(AnswerScoring.citationRecall(
                        List.of(100L, 200L), List.of("kap:100:0000", "kap:100:0001", "kap:7:0000")))
                .isEqualTo(0.5);
        assertThat(AnswerScoring.citationRecall(List.of(100L), List.of("junk"))).isZero();
        assertThat(AnswerScoring.citationRecall(List.of(), List.of())).isEqualTo(1.0);
    }

    @Test
    void refusalIsCorrectOnlyWithoutAnAnswer() {
        assertThat(AnswerScoring.refusalCorrect(EvalOutcome.REFUSED)).isTrue();
        assertThat(AnswerScoring.refusalCorrect(EvalOutcome.NO_VALID_CITATIONS)).isTrue();
        assertThat(AnswerScoring.refusalCorrect(EvalOutcome.ANSWERED)).isFalse();
        assertThat(AnswerScoring.refusalCorrect(EvalOutcome.LLM_CAP)).isNull();
        assertThat(AnswerScoring.refusalCorrect(EvalOutcome.ERROR)).isNull();
    }

    @Test
    void costIsIntegerMicrosFormattedOnlyAtTheEdge() {
        assertThat(AnswerScoring.meanMicros(2_000, 3)).isEqualTo(667); // 666.67 rounds half up
        assertThat(AnswerScoring.meanMicros(5, 2)).isEqualTo(3); // 2.5 rounds up
        assertThat(AnswerScoring.meanMicros(10, 0)).isZero();
        assertThat(AnswerScoring.formatUsd(1_234)).isEqualTo("$0.001234");
        assertThat(AnswerScoring.formatUsd(60_000)).isEqualTo("$0.060000");
        assertThat(AnswerScoring.formatUsd(2_500_000)).isEqualTo("$2.500000");
    }

    @Test
    void relativeTimeExpressionsAreFoundInTurkishAndEnglish() {
        // the sentence from the live demo run
        assertThat(AnswerScoring.relativeTimeHits("SISE için son 7 günde yeni özel durum bulunmamaktadır."))
                .containsExactly("son-N-gun/hafta/ay");
        assertThat(AnswerScoring.relativeTimeHits("Son bir haftada bildirim yok"))
                .containsExactly("son-N-gun/hafta/ay");
        assertThat(AnswerScoring.relativeTimeHits("BUGÜN ve dün açıklandı")).containsExactly("bugun", "dun");
        assertThat(AnswerScoring.relativeTimeHits("Bu hafta, geçen ay ve yakın zamanda; şu anda"))
                .containsExactly("bu-hafta/ay/yil", "gecen", "yakin-zamanda", "su-anda");
        assertThat(AnswerScoring.relativeTimeHits("Recently, today and yesterday; in the last 30 days, this week"))
                .containsExactly("recently", "today", "yesterday", "last-N-days", "this-week/month");
        assertThat(AnswerScoring.relativeTimeHits("bugun")).containsExactly("bugun");
    }

    @Test
    void absoluteDatesAndLookalikeWordsAreNotRelativeTime() {
        assertThat(AnswerScoring.relativeTimeFree(
                        "KAP'a göre 29.12.2023 tarihli son bildirim, 18 Ekim 2023 tarihinde yayımlandı [kap:1:0000]."))
                .isTrue();
        assertThat(AnswerScoring.relativeTimeFree("dünya genelinde güncel kur; son bildirim 2023-12-29"))
                .isTrue();
        assertThat(AnswerScoring.relativeTimeFree("Bugünlük")).isFalse(); // a Turkish suffix does not hide the stem
        assertThat(AnswerScoring.relativeTimeFree("")).isTrue();
    }
}
