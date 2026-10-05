package io.github.orhanyarkin.saiman.orchestrator.run;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The keyset position of a runs page: the {@code (createdAt, id)} of the last item served. Encoded as
 * unpadded base64url of {@code <ISO-8601 instant>|<uuid>}; opaque to clients.
 */
record RunCursor(Instant createdAt, UUID id) {

    String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the decoded cursor, or null if {@code text} is not a cursor this class produced. */
    static @Nullable RunCursor decode(String text) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
            int bar = plain.indexOf('|');
            if (bar < 0) {
                return null;
            }
            return new RunCursor(Instant.parse(plain.substring(0, bar)), UUID.fromString(plain.substring(bar + 1)));
        } catch (RuntimeException e) { // bad base64, instant or uuid
            return null;
        }
    }
}
