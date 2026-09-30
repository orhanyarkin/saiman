package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Untrusted prose (model output, KAP issuer text) must never carry a followable link. */
class LinkScrubbingTests {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Pay at //evil.example/pay now",
                "Click javascript:alert(1) now",
                "See data:text/html,<script>x</script> here",
                "Open vbscript:msgbox(1) here",
                "Go to evil.com/pay today",
                "Go to sub.evil.co.uk/a?b=c today",
                "Visit evil.com today",
                "Visit www.evil.com today",
                "See [x](http://evil) here",
                "See [x](evil.com/pay) here",
                "Mixed JaVaScRiPt:alert(1) case"
            })
    void linkLikeTokensAreReplaced(String text) {
        String scrubbed = GroundedGenerator.scrubLinks(text);

        assertThat(scrubbed).contains(GroundedGenerator.LINK_REMOVED);
        assertThat(scrubbed)
                .doesNotContainIgnoringCase("evil")
                .doesNotContainIgnoringCase("alert")
                .doesNotContainIgnoringCase("msgbox")
                .doesNotContain("<script>");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Genel kurul 20.06.2016 tarihinde toplandı.",
                "Tutar 59.368.579,- Euro olarak açıklanmıştır.",
                "Borusan Yatırım ve Pazarlama A.Ş. bildirimi.",
                "Metadata: tarih ve tutar bilgisi.",
                "Oran %12,5; toplam 1.250.000 TL (bkz. madde 3.2.1).",
                "T.C. Merkez Bankası, Sn. Yönetim Kurulu"
            })
    void ordinaryTurkishFinancialTextIsLeftAlone(String text) {
        assertThat(GroundedGenerator.scrubLinks(text)).isEqualTo(text);
    }

    @Test
    void modelReplyTextIsScrubbedAtParse() {
        GroundedGenerator generator =
                new GroundedGenerator(null, JsonMapper.builder().build(), null);

        GroundedGenerator.Reply reply = generator.parse(
                "{\"answer\":\"See [x](http://evil) and //evil/pay and evil.com/pay.\",\"citedChunkIds\":[\"kap:1:0000\"]}",
                "answer");

        assertThat(reply.text()).doesNotContain("evil").contains(GroundedGenerator.LINK_REMOVED);
        assertThat(reply.citedChunkIds()).isEqualTo(List.of("kap:1:0000"));
    }
}
