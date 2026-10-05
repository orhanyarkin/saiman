package io.github.orhanyarkin.saiman.orchestrator.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RunCursorTests {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private static String forged(String instant) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((instant + "|" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aCursorTheEndpointProducedRoundTrips() {
        RunCursor cursor = new RunCursor(Instant.parse("2026-05-31T10:15:30.123456Z"), UUID.randomUUID());
        assertThat(RunCursor.decode(cursor.encode(), NOW)).isEqualTo(cursor);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "+300000-01-01T00:00:00Z",
                "-300000-01-01T00:00:00Z",
                "+1000000000-12-31T23:59:59Z",
                "-1000000000-01-01T00:00:00Z",
                "1969-12-31T23:59:59Z",
                "2024-12-31T23:59:59.999999Z",
                "2026-06-02T12:00:00.000001Z",
                "9999-12-31T23:59:59Z",
                "not-an-instant"
            })
    void instantsOutsideTheRangeAreRejected(String instant) {
        assertThat(RunCursor.decode(forged(instant), NOW)).isNull();
    }

    @Test
    void theBoundsAreInclusive() {
        assertThat(RunCursor.decode(forged("2025-01-01T00:00:00Z"), NOW)).isNotNull();
        assertThat(RunCursor.decode(forged("2026-06-02T12:00:00Z"), NOW)).isNotNull();
    }
}
