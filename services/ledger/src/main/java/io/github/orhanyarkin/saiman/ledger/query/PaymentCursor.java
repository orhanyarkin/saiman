package io.github.orhanyarkin.saiman.ledger.query;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The keyset position {@code (createdAt, paymentId)} of the last payment on a page, as an opaque Base64URL token.
 * Microseconds, because that is Postgres' {@code timestamptz} precision: a lossy round-trip would skip or repeat rows.
 */
record PaymentCursor(Instant createdAt, UUID paymentId) {

    private static final Pattern RAW = Pattern.compile("-?[0-9]{1,19}:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final int MAX_TOKEN = 128;

    String encode() {
        long micros =
                Math.addExact(Math.multiplyExact(createdAt.getEpochSecond(), 1_000_000L), createdAt.getNano() / 1_000L);
        String raw = micros + ":" + paymentId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** The cursor of a token this class encoded, or empty for anything else. */
    static Optional<PaymentCursor> decode(String token) {
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
            return Optional.of(new PaymentCursor(at, UUID.fromString(raw.substring(colon + 1))));
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            return Optional.empty();
        }
    }
}
