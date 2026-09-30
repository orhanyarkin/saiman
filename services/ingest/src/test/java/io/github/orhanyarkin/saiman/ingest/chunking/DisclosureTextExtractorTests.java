package io.github.orhanyarkin.saiman.ingest.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class DisclosureTextExtractorTests {

    private static String b64(String html) {
        return Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void decodesUtf8DespiteIso88599HeaderAndDropsStyleScriptAndComments() {
        String html = "<?xml version=\"1.0\" encoding=\"ISO-8859-9\"?><html><head><style>.a{color:red}</style></head>"
                + "<body><!-- gizli --><script>alert(1)</script><p>İstanbul yolları çğıöşü</p></body></html>";

        String text = DisclosureTextExtractor.fromBase64Html(b64(html));

        assertThat(text).isEqualTo("İstanbul yolları çğıöşü");
    }

    @Test
    void tablesBecomePipeRowsAndBlocksBecomeLines() {
        String html = "<body><h1>Başlık</h1><table><tr><th>Kalem</th><th>Adet</th></tr>"
                + "<tr><td>Gövde</td><td>12</td></tr></table><p>Son</p></body>";

        assertThat(DisclosureTextExtractor.fromHtml(html)).isEqualTo("Başlık\nKalem | Adet\nGövde | 12\nSon");
    }

    @Test
    void whitespaceIsCollapsedAndOutputIsNfc() {
        String decomposed = Normalizer.normalize("çalışma    ışığı", Normalizer.Form.NFD);

        String text = DisclosureTextExtractor.fromHtml("<p>" + decomposed + "</p>");

        assertThat(text).isEqualTo("çalışma ışığı");
        assertThat(Normalizer.isNormalized(text, Normalizer.Form.NFC)).isTrue();
    }

    @Test
    void base64WithLineBreaksIsAccepted() {
        String encoded = b64("<p>merhaba dünya</p>");
        String wrapped = encoded.substring(0, 8) + "\r\n" + encoded.substring(8);

        assertThat(DisclosureTextExtractor.fromBase64Html(wrapped)).isEqualTo("merhaba dünya");
    }
}
