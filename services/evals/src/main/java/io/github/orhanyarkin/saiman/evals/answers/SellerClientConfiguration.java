package io.github.orhanyarkin.saiman.evals.answers;

import io.github.orhanyarkin.saiman.evals.EvalsProperties;
import io.github.orhanyarkin.saiman.evals.ingest.IngestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Tier A wiring, only when {@code saiman.evals.answers.enabled=true}. With the tier on, a blank or malformed token
 * or an unusable seller URL fails the application at start with a message that never quotes the token.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("saiman.evals.answers.enabled")
class SellerClientConfiguration {

    @Bean
    SellerClient sellerClient(RestClient.Builder builder, EvalsProperties properties) {
        EvalsProperties.Seller seller = properties.seller();
        String token = seller.requireServiceToken();
        String baseUrl = checkedBaseUrl(seller.baseUrl());
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(seller.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER) // the bearer token must never follow a redirect
                .build());
        requests.setReadTimeout(seller.readTimeout());
        RestClient client = builder.baseUrl(baseUrl)
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        return new SellerClient(client, seller.retryAttempts(), seller.retryWait());
    }

    @Bean
    AnswerRunner answerRunner(EvalsProperties properties, SellerClient seller, IngestClient ingest) {
        return new AnswerRunner(properties, seller, ingest);
    }

    private static String checkedBaseUrl(String baseUrl) {
        if (baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "saiman.evals.answers.enabled=true needs saiman.evals.seller.base-url (e.g. http://seller-api:8081)");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("saiman.evals.seller.base-url is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https"))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null) {
            throw new IllegalStateException(
                    "saiman.evals.seller.base-url must be http(s)://host[:port] without user info");
        }
        return baseUrl.strip();
    }
}
