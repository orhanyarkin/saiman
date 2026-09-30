package io.github.orhanyarkin.saiman.orchestrator.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Validated, code-rendered tool arguments: a ticker of the allowed shape and, for tools that take
 * one, a cleaned question. Only {@link PaidToolGateway} builds these from model input.
 *
 * @param ticker upper-case, {@code [A-Z0-9]{3,6}}
 * @param question cleaned, 3..500 code points; null for tools without a question
 */
public record ToolArguments(String ticker, @Nullable String question) {

    static final Pattern TICKER = Pattern.compile("[A-Z0-9]{3,6}");

    public ToolArguments {
        if (!TICKER.matcher(ticker).matches()) {
            throw new IllegalArgumentException("ticker is not of the allowed shape");
        }
        if (question != null
                && UntrustedText.question(question).filter(question::equals).isEmpty()) {
            throw new IllegalArgumentException("question is not a cleaned 3..500 character question");
        }
    }

    /** The canonical rendering: what events show and what the dedupe hash covers. */
    public String render() {
        StringBuilder out = new StringBuilder("{\"ticker\":\"").append(ticker).append('"');
        if (question != null) {
            out.append(",\"question\":\"").append(jsonEscape(question)).append('"');
        }
        return out.append('}').toString();
    }

    /** SHA-256 over the tool name and the canonical rendering, hex. */
    public String hash(String tool) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((tool + "\n" + render()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static String jsonEscape(String text) {
        // A cleaned question has no control characters left; only quotes and backslashes need escaping.
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
