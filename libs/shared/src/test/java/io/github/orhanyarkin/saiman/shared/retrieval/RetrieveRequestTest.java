package io.github.orhanyarkin.saiman.shared.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RetrieveRequestTest {

    @Test
    void acceptsAValidRequestAndCopiesTheTickerList() {
        RetrieveRequest request = new RetrieveRequest("THY 2023 temettü kararı nedir?", List.of("THYAO"), 8);
        assertThat(request.tickers()).containsExactly("THYAO");
    }

    @Test
    void rejectsBlankOrOversizedQueries() {
        assertThatThrownBy(() -> new RetrieveRequest(" ", List.of(), 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrieveRequest("x".repeat(501), List.of(), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedTickersAndOutOfRangeTopK() {
        assertThatThrownBy(() -> new RetrieveRequest("q", List.of("thyao"), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrieveRequest("q", List.of("THYAO"), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrieveRequest("q", List.of("THYAO"), 21))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooManyTickers() {
        List<String> eleven = List.of("AAA", "BBB", "CCC", "DDD", "EEE", "FFF", "GGG", "HHH", "III", "JJJ", "KKK");
        assertThatThrownBy(() -> new RetrieveRequest("q", eleven, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validationMessagesNeverEchoTheInput() {
        assertThatThrownBy(() -> new RetrieveRequest("q", List.of("bad<script>"), 5))
                .hasMessageNotContaining("script");
    }
}
