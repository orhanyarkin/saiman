package io.github.orhanyarkin.x402.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.client.SpendGuard;
import io.github.orhanyarkin.x402.client.X402PaymentInterceptor;
import io.github.orhanyarkin.x402.evm.PaymentSigner;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Verifies that {@link ConsoleBuyerApplication} boots as a plain (non-web) Spring Boot application
 * once {@code x402.client.*} is configured, and that all the beans the commands depend on are
 * present. Uses {@link ApplicationContextRunner} against the application class directly (not
 * {@link SpringBootTest}, which would need the whole {@code ConsoleBuyerApplication.main} argument
 * plumbing) with a test-only private key, never a real one.
 */
class ConsoleBuyerApplicationContextTest {

    // The well-known "cow" test private key (keccak256("cow")); never a real wallet key.
    private static final String COW_PRIVATE_KEY = "0xc85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4";
    private static final String PAY_TO = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConsoleBuyerApplication.class)
            .withPropertyValues(
                    "x402.client.private-key=" + COW_PRIVATE_KEY,
                    "x402.client.max-amount-per-request=1000000",
                    "x402.client.allowed-pay-to=" + PAY_TO);

    @Test
    void contextLoadsWithAllCommandBeans() {
        runner.run((AssertableApplicationContext context) -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PaymentSigner.class);
            assertThat(context).hasSingleBean(SpendGuard.class);
            assertThat(context).hasSingleBean(X402PaymentInterceptor.class);
            assertThat(context).hasSingleBean(BuyCommand.class);
            assertThat(context).hasSingleBean(ReplayCommand.class);
            assertThat(context).hasSingleBean(TestnetCheckCommand.class);
        });
    }

    private ConfigurableApplicationContext realContext;

    @AfterEach
    void closeRealContext() {
        System.clearProperty("X402_SELLER_PAYTO_ADDRESS");
        if (realContext != null) {
            realContext.close();
        }
    }

    /**
     * Unlike {@link #contextLoadsWithAllCommandBeans()} (an {@link ApplicationContextRunner}, which
     * never processes classpath {@code application.yaml}), this boots a real {@link
     * SpringApplicationBuilder} the same way {@link ConsoleBuyerApplication#run} does, so it
     * actually exercises this module's {@code src/main/resources/application.yaml} — proving its
     * {@code ${X402_SELLER_PAYTO_ADDRESS:}} placeholder and {@code
     * spring.http.clients.redirects} both resolve as intended, not just that equivalent property
     * values placed directly on a runner would.
     */
    @Test
    void applicationYamlResolvesTheDocumentedDefaults() {
        System.setProperty("X402_SELLER_PAYTO_ADDRESS", PAY_TO);
        realContext = new SpringApplicationBuilder(ConsoleBuyerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of("x402.client.private-key", COW_PRIVATE_KEY))
                .run();

        assertThat(realContext.getEnvironment().getProperty("x402.client.max-amount-per-request"))
                .isEqualTo("100000");
        assertThat(realContext.getEnvironment().getProperty("x402.client.allowed-pay-to"))
                .isEqualTo(PAY_TO);
        assertThat(realContext.getEnvironment().getProperty("spring.http.clients.redirects"))
                .isEqualTo("dont-follow");
        assertThat(realContext.getBeansOfType(X402PaymentInterceptor.class)).hasSize(1);
    }
}
