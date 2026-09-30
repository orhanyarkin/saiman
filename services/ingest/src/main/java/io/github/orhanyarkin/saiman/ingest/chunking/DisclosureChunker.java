package io.github.orhanyarkin.saiman.ingest.chunking;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * Splits a disclosure into chunks of about {@code targetTokens} tokens with Spring AI's {@link
 * TokenTextSplitter}. The subject is prepended so the first chunk carries the header; table rows
 * arrive as {@code a | b} lines from the extractor and stay whole because the splitter only cuts
 * at sentence or line boundaries. Deterministic: the same input always yields the same chunks, so
 * chunk numbers (and therefore chunk ids) are stable across runs.
 */
public class DisclosureChunker {

    private final TokenTextSplitter splitter;

    public DisclosureChunker(int targetTokens) {
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(targetTokens)
                .withMinChunkSizeChars(150)
                .withMinChunkLengthToEmbed(1)
                .withKeepSeparator(true)
                .build();
    }

    public List<String> chunk(String title, String body) {
        String text = title.isBlank() ? body : title.strip() + "\n" + body;
        if (text.isBlank()) {
            return List.of();
        }
        return splitter.split(List.of(new Document(text))).stream()
                .map(Document::getText)
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .map(String::strip)
                .toList();
    }
}
