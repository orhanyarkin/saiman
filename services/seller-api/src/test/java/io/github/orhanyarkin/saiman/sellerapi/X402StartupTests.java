package io.github.orhanyarkin.saiman.sellerapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.StartupDatabase;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * ADR-0008/ADR-0009's fail-closed rule: {@code DisclosureSummaryController} is a {@code
 * @RequiresPayment} handler, so {@code seller-api} must never start without a valid {@code
 * x402.server.pay-to} -- there is no {@code enabled} flag to bypass this.
 *
 * <p>Boots the real {@link SellerApiApplication} entry point (not a synthetic test slice) with
 * {@code x402.server.pay-to} forced blank, the same failure {@code
 * x402.server.pay-to: ${X402_SELLER_PAYTO_ADDRESS}} (the production wiring in {@code
 * application.yaml}, no default value) produces when {@code X402_SELLER_PAYTO_ADDRESS} is not set
 * -- a genuinely-absent-env-var run is not reproducible from inside this test JVM, since this
 * workspace's own shell already exports a valid {@code X402_SELLER_PAYTO_ADDRESS} for local {@code
 * make up} convenience.
 */
class X402StartupTests {

    private static final FakeFacilitator FACILITATOR = new FakeFacilitator();

    @AfterAll
    static void stopFacilitator() {
        FACILITATOR.close();
    }

    @Test
    void startupFailsWithoutAValidPayToAddress() {
        // Command-line args outrank classpath:/application.yaml in Spring Boot's property source
        // order, so "--x402.server.pay-to=" shadows the whole `${X402_SELLER_PAYTO_ADDRESS}`
        // placeholder entry from application.yaml outright (Spring does not merge/re-resolve a
        // lower-priority source's raw value once a higher-priority source defines the same key) --
        // unlike SpringApplicationBuilder#properties(...), which adds only lowest-priority
        // *default* properties and would be shadowed by application.yaml's own entry here.
        Throwable thrown = assertThrows(
                RuntimeException.class,
                () -> new SpringApplicationBuilder(SellerApiApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .run(StartupDatabase.with(
                                "--server.port=0",
                                "--x402.server.facilitator.url=" + FACILITATOR.url(),
                                "--x402.server.pay-to=")));

        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(IllegalStateException.class).hasMessageContaining("pay-to");
    }
}
