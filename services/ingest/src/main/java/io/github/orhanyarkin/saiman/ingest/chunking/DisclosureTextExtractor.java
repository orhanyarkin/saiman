package io.github.orhanyarkin.saiman.ingest.chunking;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

/**
 * Turns a KAP {@code htmlMessages[].tr} value into plain text (ADR-0010): base64 decode, UTF-8
 * (the XML header says ISO-8859-9 but the bytes are UTF-8), drop {@code <style>}, {@code <script>}
 * and comments, keep block structure as line breaks and table rows as {@code a | b | c}, NFC.
 */
public final class DisclosureTextExtractor {

    private static final Set<String> BREAKING = Set.of(
            "p", "div", "br", "li", "ul", "ol", "table", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "section", "hr");
    private static final Set<String> CELLS = Set.of("td", "th");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00a0\\u200b]+");

    private DisclosureTextExtractor() {}

    public static String fromBase64Html(String base64) {
        byte[] bytes = Base64.getMimeDecoder().decode(base64);
        return fromHtml(new String(bytes, StandardCharsets.UTF_8));
    }

    public static String fromHtml(String html) {
        Document document = Jsoup.parse(html);
        document.select("style, script, noscript").remove();
        StringBuilder out = new StringBuilder();
        document.body().traverse(new TextVisitor(out));
        List<String> lines = new ArrayList<>();
        for (String raw : out.toString().split("\n", -1)) {
            String line = WHITESPACE.matcher(raw).replaceAll(" ").strip();
            while (line.endsWith("|")) {
                line = line.substring(0, line.length() - 1).strip();
            }
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return Normalizer.normalize(String.join("\n", lines), Normalizer.Form.NFC);
    }

    private static final class TextVisitor implements NodeVisitor {

        private final StringBuilder out;

        TextVisitor(StringBuilder out) {
            this.out = out;
        }

        @Override
        public void head(Node node, int depth) {
            // Comments and data nodes are separate node types and are simply not visited as text.
            if (node instanceof TextNode text) {
                out.append(text.getWholeText());
            } else if (node instanceof Element element && BREAKING.contains(element.normalName())) {
                out.append('\n');
            }
        }

        @Override
        public void tail(Node node, int depth) {
            if (node instanceof Element element) {
                if (CELLS.contains(element.normalName())) {
                    out.append(" | ");
                } else if (BREAKING.contains(element.normalName())) {
                    out.append('\n');
                }
            }
        }
    }
}
