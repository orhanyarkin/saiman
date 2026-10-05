package io.github.orhanyarkin.saiman.orchestrator.run;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The keyset position of a runs page: the {@code (createdAt, id)} of the last item served. Encoded as
 * unpadded base64url of {@code <ISO-8601 instant>|<uuid>}; opaque to clients.
 */
record RunCursor(Instant createdAt, UUID id) {

    /** No run predates the project; anything earlier is a forged cursor. */
    static final Instant EARLIEST = Instant.parse("2025-01-01T00:00:00Z");

    String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the decoded cursor, or null if {@code text} is not a cursor this class produced. */
    static @Nullable RunCursor decode(String text) {
        return decode(text, Instant.now());
    }

    /**
     * As {@link #decode(String)} with an explicit clock reading. The instant must lie in [{@link
     * #EARLIEST}, now + 1 day]: a forged value outside it would otherwise reach the database as an
     * out-of-range timestamp.
     */
    static @Nullable RunCursor decode(String text, Instant now) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
            int bar = plain.indexOf('|');
            if (bar < 0) {
                return null;
            }
            Instant createdAt = Instant.parse(plain.substring(0, bar));
            if (createdAt.isBefore(EARLIEST) || createdAt.isAfter(now.plus(Duration.ofDays(1)))) {
                return null;
            }
            return new RunCursor(createdAt, UUID.fromString(plain.substring(bar + 1)));
        } catch (RuntimeException e) { // bad base64, instant or uuid
            return null;
        }
    }
}
