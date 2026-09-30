package io.github.orhanyarkin.saiman.ingest.mkk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ingest.IngestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

class MkkEndpointPolicyTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IngestProperties.class)
    static class Support {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Support.class, MkkConfiguration.class);

    @Test
    void theDefaultBaseUrlStarts() {
        runner.run(context -> assertThat(context).hasSingleBean(MkkClient.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://apigwdev.mkk.com.tr/api/vyk", "https://APIGW.mkk.com.tr/api/vyk"})
    void allowlistedHttpsHostsStart(String url) {
        runner.withPropertyValues("saiman.ingest.mkk.base-url=" + url)
                .run(context -> assertThat(context).hasSingleBean(MkkClient.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://apigwdev.mkk.com.tr/api/vyk",
                "https://evil.example/api/vyk",
                "https://apigwdev.mkk.com.tr.evil.example/api/vyk",
                "https://user:secret@apigwdev.mkk.com.tr/api/vyk",
                "https://127.0.0.1:8443/api/vyk",
                "ftp://apigw.mkk.com.tr/x",
                "not a uri"
            })
    void anythingElseFailsStartupWithoutEchoingTheUrl(String url) {
        runner.withPropertyValues("saiman.ingest.mkk.base-url=" + url).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).doesNotContain("secret").doesNotContain("evil");
        });
    }

    @Test
    void aPolicyBeanIsTheOnlyWayToRelaxTheCheck() {
        runner.withBean(MkkEndpointPolicy.class, () -> baseUrl -> {})
                .withPropertyValues("saiman.ingest.mkk.base-url=http://localhost:1/api")
                .run(context -> assertThat(context).hasSingleBean(MkkClient.class));
    }
}
