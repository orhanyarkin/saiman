package io.github.orhanyarkin.x402.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;

/**
 * Verifies the three x402 auto-configuration classes are registered and each starts a plain
 * {@link org.springframework.boot.autoconfigure.AutoConfiguration}-only context cleanly.
 */
class X402AutoConfigurationImportsTest {

    @Test
    void allThreeAutoConfigurationsAreRegisteredInTheImportsFile() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
                .contains(
                        X402ServerAutoConfiguration.class.getName(),
                        X402ClientAutoConfiguration.class.getName(),
                        X402ObservationAutoConfiguration.class.getName());
    }

    @Test
    void serverAutoConfigurationStartsCleanly() {
        // A WebApplicationContextRunner (not a plain ApplicationContextRunner), plus
        // WebMvcAutoConfiguration and RestClientAutoConfiguration: a real Spring MVC application
        // always has RequestMappingHandlerMapping and RestClient.Builder beans whenever
        // spring-boot-starter-webmvc/-restclient are present (Boot's own auto-configurations, not
        // something only this starter's auto-configuration class provides), and
        // WebMvcAutoConfiguration itself is @ConditionalOnWebApplication(SERVLET).
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        X402ServerAutoConfiguration.class,
                        X402ObservationAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        RestClientAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(X402ServerAutoConfiguration.class);
                });
    }

    @Test
    void clientAutoConfigurationStartsCleanly() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(X402ClientAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(X402ClientAutoConfiguration.class);
                });
    }

    @Test
    void observationAutoConfigurationStartsCleanly() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(X402ObservationAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(X402ObservationAutoConfiguration.class);
                });
    }

    @Test
    void allThreeTogetherStartCleanly() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        X402ServerAutoConfiguration.class,
                        X402ClientAutoConfiguration.class,
                        X402ObservationAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        RestClientAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed());
    }
}
