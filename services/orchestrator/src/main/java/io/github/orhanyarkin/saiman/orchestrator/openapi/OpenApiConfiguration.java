package io.github.orhanyarkin.saiman.orchestrator.openapi;

import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI contract of the orchestrator (ADR-0022). springdoc itself is off unless {@code
 * springdoc.api-docs.enabled=true} (production never serves a document; {@code OpenApiContractTests}
 * turns it on). springdoc collects every {@link ModelConverter} bean, so registering the nullability
 * converter is enough. The {@code bearerAuth} scheme (ADR-0023) applies to every operation: the API is called with
 * {@code Authorization: Bearer <token>} (READER for reads, OPERATOR for starting runs and deciding approvals).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static final String BEARER_AUTH = "bearerAuth";

    @Bean
    ModelConverter nullabilityModelConverter() {
        return new NullabilityModelConverter();
    }

    @Bean
    OpenAPI orchestratorOpenApi() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_AUTH,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .description("A static role token (READER or OPERATOR), sent as"
                                                + " Authorization: Bearer <token>. Never in the URL.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
