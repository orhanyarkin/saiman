package io.github.orhanyarkin.saiman.orchestrator.openapi;

import io.swagger.v3.core.converter.ModelConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI contract of the orchestrator (ADR-0022). springdoc itself is off unless {@code
 * springdoc.api-docs.enabled=true} (production never serves a document; {@code OpenApiContractTests}
 * turns it on). springdoc collects every {@link ModelConverter} bean, so registering the nullability
 * converter is enough.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    ModelConverter nullabilityModelConverter() {
        return new NullabilityModelConverter();
    }
}
