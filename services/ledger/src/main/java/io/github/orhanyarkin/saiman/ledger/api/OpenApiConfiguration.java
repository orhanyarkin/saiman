package io.github.orhanyarkin.saiman.ledger.api;

import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ledger's OpenAPI document, the contract the dashboard's TypeScript client is generated from (ADR-0022,
 * {@code docs/api/ledger.openapi.json}). {@code /v3/api-docs} is off at runtime ({@code springdoc.api-docs.enabled}
 * is false in {@code application.yaml}); the contract test turns it on. Every operation needs a bearer token
 * ({@value #BEARER_AUTH} scheme, ADR-0023): READER for reads, OPERATOR to start a reconciliation run.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ModelConverter.class)
class OpenApiConfiguration {

    /** Name of the security scheme in the document. */
    static final String BEARER_AUTH = "bearerAuth";

    @Bean
    NullabilityOpenApiCustomizer nullabilityOpenApiCustomizer() {
        return new NullabilityOpenApiCustomizer();
    }

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Saiman ledger API")
                        .version("v1")
                        .description("Double-entry ledger, seller revenue and on-chain reconciliation (testnet only)."))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_AUTH,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .description("Opaque API token (READER or OPERATOR), sent as"
                                                + " Authorization: Bearer <token>; never in a URL.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
