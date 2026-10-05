package io.github.orhanyarkin.saiman.sellerapi.http;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.core.StreamReadConstraints;

/**
 * Wires the request limits. The body-size filter is registered explicitly with an order just before
 * the x402 settlement filter ({@code LOWEST_PRECEDENCE - 100}), so an oversized body is answered
 * before any payment processing. The JSON parser gets a string-length cap as a second line of
 * defence (the body cap already bounds everything the paid endpoints read from a client).
 */
@Configuration(proxyBeanMethods = false)
class HttpLimitsConfiguration {

    /** Runs before {@code X402SettlementFilter} (order {@code LOWEST_PRECEDENCE - 100}). */
    static final int BODY_LIMIT_FILTER_ORDER = Ordered.LOWEST_PRECEDENCE - 200;

    @Bean
    FilterRegistrationBean<BodySizeLimitFilter> bodySizeLimitFilterRegistration(HttpLimitsProperties properties) {
        FilterRegistrationBean<BodySizeLimitFilter> registration =
                new FilterRegistrationBean<>(new BodySizeLimitFilter(properties.maxBodyBytes()));
        registration.setName("bodySizeLimitFilter");
        // The paid API and the internal API (the eval POST): the same body cap for both.
        registration.addUrlPatterns("/v1/*", "/internal/*");
        registration.setOrder(BODY_LIMIT_FILTER_ORDER);
        return registration;
    }

    @Bean
    JsonFactoryBuilderCustomizer jsonStringLengthLimit(HttpLimitsProperties properties) {
        return builder -> builder.streamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(properties.maxJsonStringLength())
                .build());
    }
}
