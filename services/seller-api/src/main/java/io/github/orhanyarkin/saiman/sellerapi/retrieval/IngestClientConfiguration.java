package io.github.orhanyarkin.saiman.sellerapi.retrieval;

import java.net.URI;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link IngestClient} only in RAG mode. Fails startup when the base URL is missing or
 * not http(s): a RAG-mode seller without a corpus must not come up and later serve empty answers.
 * Redirects are off, so an ingest answer can never bounce this client to another host.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "seller.disclosures.source", havingValue = "rag")
class IngestClientConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IngestClient ingestClient(RestClient.Builder builder, IngestProperties properties) {
        String baseUrl = properties.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("seller.ingest.base-url is required when seller.disclosures.source=rag");
        }
        URI uri = URI.create(baseUrl.strip());
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new IllegalStateException("seller.ingest.base-url must be an http(s) URL");
        }
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        RestClient client =
                builder.clone().baseUrl(uri.toString()).requestFactory(factory).build();
        return new IngestClient(client, properties);
    }
}
