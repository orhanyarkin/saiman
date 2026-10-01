package io.github.orhanyarkin.saiman.orchestrator.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The paying client's read timeout must outlast the latest moment a settled answer can arrive. */
class SellerPropertiesTests {

    private static final URI BASE = URI.create("http://seller-api:8081");

    @Test
    void theDefaultReadTimeoutIsSixtyFiveSeconds() {
        SellerProperties bound = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("saiman.orchestrator.seller", SellerProperties.class);

        assertThat(bound.readTimeout()).isEqualTo(Duration.ofSeconds(65));
    }

    @Test
    void aReadTimeoutBelowTheAuthorizationWindowFailsStartup() {
        // 35 s was the old default: a 200 the seller settled 40 s in would be cut off and held.
        assertThatThrownBy(() -> new SellerProperties(BASE, Duration.ofSeconds(5), Duration.ofSeconds(35), 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-timeout must be at least 65s");
        assertThatThrownBy(() -> new SellerProperties(BASE, Duration.ofSeconds(5), Duration.ofMillis(64_999), 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SellerProperties(BASE, Duration.ofSeconds(5), Duration.ofSeconds(65), 30).readTimeout())
                .isEqualTo(SellerProperties.MIN_READ_TIMEOUT);
    }
}
