package io.github.orhanyarkin.saiman.ledger;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import org.springframework.boot.resttestclient.autoconfigure.RestTestClientBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

/**
 * Makes the auto-configured {@code RestTestClient} send an OPERATOR token (ADR-0023), which is also a READER through
 * the role hierarchy, so the API tests go through the real security chain. Tests about authentication itself set
 * another {@code Authorization} header per request or remove it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OperatorTokenClient {

    @Bean
    RestTestClientBuilderCustomizer operatorToken() {
        return builder -> builder.defaultHeader(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.OPERATOR));
    }
}
