package io.github.orhanyarkin.saiman.evals.ingest;

import io.github.orhanyarkin.saiman.evals.EvalsProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class IngestClientConfiguration {

    @Bean
    IngestClient ingestClient(RestClient.Builder builder, EvalsProperties properties) {
        EvalsProperties.Ingest ingest = properties.ingest();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(ingest.readTimeout()).build());
        requests.setReadTimeout(ingest.readTimeout());
        RestClient client =
                builder.baseUrl(ingest.baseUrl()).requestFactory(requests).build();
        return new IngestClient(client, ingest.retryAttempts(), ingest.retryWait());
    }
}
