package io.github.orhanyarkin.saiman.orchestrator;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.RestTestClientBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

/**
 * The shared Postgres (ADR-0020): one container per test JVM; each application context gets its own
 * database on the shared Postgres. Same images as deploy/compose.
 *
 * <p>The auto-configured {@code RestTestClient} sends the OPERATOR test token by default (the digests are in {@code
 * config/application.yaml}), so the existing API tests exercise the real security chain. Tests that need another
 * identity (none, READER) use {@code client.mutate().defaultHeaders(...)} to replace it.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresContainerConfiguration.class)
public class TestcontainersConfiguration {

    @Bean
    RestTestClientBuilderCustomizer operatorTokenByDefault() {
        return builder -> builder.defaultHeader(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.OPERATOR));
    }
}
