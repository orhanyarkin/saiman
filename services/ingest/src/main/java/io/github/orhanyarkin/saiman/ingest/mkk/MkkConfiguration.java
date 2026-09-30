package io.github.orhanyarkin.saiman.ingest.mkk;

import io.github.orhanyarkin.saiman.ingest.IngestProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Wires the MKK {@link RestClient}: redirects off, explicit timeouts, credential as a default header. */
@Configuration(proxyBeanMethods = false)
class MkkConfiguration {

    @Bean
    MkkClient mkkClient(
            RestClient.Builder builder,
            IngestProperties properties,
            ObjectProvider<BuildProperties> build,
            ObjectProvider<MeterRegistry> meters) {
        IngestProperties.Mkk mkk = properties.mkk();
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withRedirects(HttpRedirects.DONT_FOLLOW)
                .withConnectTimeout(mkk.connectTimeout())
                .withReadTimeout(mkk.readTimeout());
        @Nullable BuildProperties buildProperties = build.getIfAvailable();
        String buildVersion = buildProperties == null ? null : buildProperties.getVersion();
        String version = buildVersion == null ? "dev" : buildVersion;
        RestClient client = MkkClient.configure(builder.clone(), mkk, version)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
        return new MkkClient(client, mkk, meters.getIfAvailable());
    }
}
