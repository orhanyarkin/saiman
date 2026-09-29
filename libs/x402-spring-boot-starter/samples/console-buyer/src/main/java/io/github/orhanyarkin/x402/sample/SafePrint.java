package io.github.orhanyarkin.x402.sample;

/**
 * Makes server-supplied text (a response body, a facilitator {@code invalidReason}/{@code
 * invalidMessage}, an x402 {@code errorReason}/{@code errorMessage}...) safe to print to a
 * terminal: control characters (which could otherwise move the cursor, clear the screen or forge
 * fake output) are stripped, and the result is capped to a bounded length so a hostile or
 * misbehaving server can't flood this process's stdout/stderr.
 */
final class SafePrint {

    private static final int MAX_LENGTH = 200;

    private SafePrint() {}

    /** @return {@code value}, control-character-free and at most {@value #MAX_LENGTH} characters */
    static String of(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder();
        boolean truncated = false;
        for (int i = 0; i < value.length(); i++) {
            if (sanitized.length() >= MAX_LENGTH) {
                truncated = true;
                break;
            }
            char c = value.charAt(i);
            if (c >= 0x20 && c != 0x7f) {
                sanitized.append(c);
            }
        }
        return truncated ? sanitized + "..." : sanitized.toString();
    }
}
