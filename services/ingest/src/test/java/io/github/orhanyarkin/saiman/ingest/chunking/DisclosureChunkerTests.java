package io.github.orhanyarkin.saiman.ingest.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DisclosureChunkerTests {

    private static String longBody() {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 80; i++) {
            body.append("İlke ")
                    .append(i)
                    .append(
                            ": yönetim kurulu bağımsız üyelerin görevlendirilmesi ve denetim komitesinin esasları uyum sağlanmıştır.\n");
        }
        return body.toString().strip();
    }

    @Test
    void shortDisclosureIsOneChunkStartingWithTheTitle() {
        List<String> chunks = new DisclosureChunker(400).chunk("Filo yatırımı", "Kısa metin.");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).startsWith("Filo yatırımı\n").contains("Kısa metin.");
    }

    @Test
    void longDisclosureSplitsIntoSeveralChunksDeterministically() {
        DisclosureChunker chunker = new DisclosureChunker(400);

        List<String> first = chunker.chunk("Uyum raporu", longBody());
        List<String> second = new DisclosureChunker(400).chunk("Uyum raporu", longBody());

        assertThat(first).hasSizeGreaterThan(2).isEqualTo(second);
        assertThat(first).allSatisfy(c -> assertThat(c).isNotBlank().isEqualTo(c.strip()));
    }

    @Test
    void smallerTargetGivesMoreChunks() {
        assertThat(new DisclosureChunker(100).chunk("t", longBody()).size())
                .isGreaterThan(new DisclosureChunker(400).chunk("t", longBody()).size());
    }

    @Test
    void emptyInputGivesNoChunks() {
        assertThat(new DisclosureChunker(400).chunk("", "  ")).isEmpty();
    }
}
