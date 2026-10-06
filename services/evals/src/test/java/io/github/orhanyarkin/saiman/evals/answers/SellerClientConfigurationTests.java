package io.github.orhanyarkin.saiman.evals.answers;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.evals.EvalsProperties;
import io.github.orhanyarkin.saiman.evals.ingest.IngestClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Start-up validation of the answer tier's configuration. */
class SellerClientConfigurationTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EvalsProperties.class)
    static class Props {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, SellerClientConfiguration.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(IngestClient.class, () -> new IngestClient(RestClient.create(), 1, Duration.ofMillis(1)));

    private static final String GOOD = "tkn_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";

    @Test
    void disabledCreatesNoSellerBeans() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(SellerClient.class).doesNotHaveBean(AnswerRunner.class));
    }

    @Test
    void enabledWithABlankTokenFailsFastWithAClearMessage() {
        runner.withPropertyValues(
                        "saiman.evals.answers.enabled=true", "saiman.evals.seller.base-url=http://seller-api:8081")
                .run(ctx -> assertThat(ctx.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("saiman.evals.seller.service-token"));
    }

    @Test
    void aMalformedTokenFailsWithoutQuotingIt() {
        runner.withPropertyValues(
                        "saiman.evals.answers.enabled=true",
                        "saiman.evals.seller.base-url=http://seller-api:8081",
                        "saiman.evals.seller.service-token=short token!")
                .run(ctx -> assertThat(ctx.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("malformed")
                        .message()
                        .doesNotContain("short token"));
    }

    @Test
    void aBlankOrUserInfoUrlFails() {
        runner.withPropertyValues("saiman.evals.answers.enabled=true", "saiman.evals.seller.service-token=" + GOOD)
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("base-url"));
        runner.withPropertyValues(
                        "saiman.evals.answers.enabled=true",
                        "saiman.evals.seller.service-token=" + GOOD,
                        "saiman.evals.seller.base-url=http://user:pw@seller-api:8081")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("base-url"));
    }

    @Test
    void aGoodConfigurationCreatesTheBeansAndToStringRedactsTheToken() {
        runner.withPropertyValues(
                        "saiman.evals.answers.enabled=true",
                        "saiman.evals.seller.base-url=http://seller-api:8081",
                        "saiman.evals.seller.service-token= " + GOOD + "\n")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SellerClient.class).hasSingleBean(AnswerRunner.class);
                    EvalsProperties props = ctx.getBean(EvalsProperties.class);
                    assertThat(props.seller().serviceToken()).isEqualTo(GOOD); // whitespace stripped
                    assertThat(props.toString()).doesNotContain(GOOD);
                    assertThat(props.seller().toString()).doesNotContain(GOOD);
                });
    }
}
