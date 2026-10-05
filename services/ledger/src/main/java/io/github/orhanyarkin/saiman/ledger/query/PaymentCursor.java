package io.github.orhanyarkin.saiman.ledger.query;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The keyset position {@code (createdAt, paymentId)} of the last payment on a page, as an opaque Base64URL token.
 * Microseconds, because that is Postgres' {@code timestamptz} precision: a lossy round-trip would skip or repeat rows.
 * A decoded instant must lie in [{@link #EARLIEST}, now + 1 day]: anything else was not issued here, and a far-off
 * value would otherwise reach Postgres ({@code timestamp out of range}) as a 500.
 */
record PaymentCursor(Instant createdAt, UUID paymentId) {

    private static final Pattern RAW = Pattern.compile("-?[0-9]{1,19}:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final int MAX_TOKEN = 128;

    /** No payment predates the project; earlier cursors are forged. */
    static final Instant EARLIEST = Instant.parse("2025-01-01T00:00:00Z");

    private static final Duration FUTURE_SKEW = Duration.ofDays(1);

    String encode() {
        long micros =
                Math.addExact(Math.multiplyExact(createdAt.getEpochSecond(), 1_000_000L), createdAt.getNano() / 1_000L);
        String raw = micros + ":" + paymentId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** The cursor of a token this class encoded, or empty for anything else (including an instant out of range). */
    static Optional<PaymentCursor> decode(String token, Instant now) {
        if (token.isEmpty() || token.length() > MAX_TOKEN) {
            return Optional.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
            if (!RAW.matcher(raw).matches()) {
                return Optional.empty();
            }
            int colon = raw.indexOf(':');
            long micros = Long.parseLong(raw.substring(0, colon));
            Instant at = Instant.ofEpochSecond(
                    Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
            if (at.isBefore(EARLIEST) || at.isAfter(now.plus(FUTURE_SKEW))) {
                return Optional.empty();
            }
            return Optional.of(new PaymentCursor(at, UUID.fromString(raw.substring(colon + 1))));
        } catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
            return Optional.empty();
        }
    }
}
