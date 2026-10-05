package io.github.orhanyarkin.saiman.ingest.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecencyIntentTests {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "THYAO son özel durum açıklamaları",
                "THYAO SON ÖZEL DURUM AÇIKLAMALARI",
                "En Son bildirimler",
                "ASELS güncel açıklamalar",
                "ASELS GÜNCEL açıklamalar",
                "guncel bildirimler",
                "Yeni bildirimler neler?",
                "YENI bildirimler neler?",
                "YENİ bildirimler neler?",
                "latest disclosures of THYAO",
                "Most RECENT filings",
                "son, güncel; bildirim!"
            })
    void detectsRecencyIntent(String question) {
        assertThat(RecencyIntent.detect(question)).as(question).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "sonuç açıklaması",
                "sonra ne oldu",
                "sonlandırılan sözleşme",
                "THYAO payların geri alınmasına ilişkin bildirim",
                "yenilenebilir enerji yatırımı",
                "güncelleme formu",
                "yeni iş ilişkisi bildirimi",
                "YENİ İŞ İLİŞKİSİ",
                "recently",
                ""
            })
    void ignoresWordsThatMerelyContainAKeyword(String question) {
        assertThat(RecencyIntent.detect(question)).as(question).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"yeni iş ilişkisi ve son durum", "Yeni İş İlişkisi için son açıklama"})
    void theDisclosureTypePhraseDoesNotHideARealKeyword(String question) {
        assertThat(RecencyIntent.detect(question)).as(question).isTrue();
    }
}
