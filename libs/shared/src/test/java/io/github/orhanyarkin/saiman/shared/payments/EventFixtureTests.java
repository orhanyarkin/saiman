package io.github.orhanyarkin.saiman.shared.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.shared.ledger.EntryPosted;
import io.github.orhanyarkin.saiman.shared.ledger.ReconciliationMismatch;
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Golden fixtures (src/test/resources/fixtures/events) round-trip exactly: the wire contract of docs/events. */
class EventFixtureTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static Stream<Arguments> fixtures() {
        return Stream.of(
                Arguments.of("payments.authorized.v1", PaymentAuthorized.class),
                Arguments.of("payments.settled.v1", PaymentSettled.class),
                Arguments.of("payments.failed.v1", PaymentFailed.class),
                Arguments.of("ledger.entry-posted.v1", EntryPosted.class),
                Arguments.of("ledger.reconciliation-mismatch.v1", ReconciliationMismatch.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void fixtureRoundTripsWithoutAddingOrLosingFields(String topic, Class<?> type) throws IOException {
        JsonNode expected;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/events/" + topic + ".json")) {
            expected = JSON.readTree(in);
        }

        Object event = JSON.treeToValue(expected, type);
        // Through text, as a consumer sees it: valueToTree keeps long-typed nodes that never equal parsed ints.
        JsonNode actual = JSON.readTree(JSON.writeValueAsString(event));

        assertThat(actual).isEqualTo(expected);
    }
}
