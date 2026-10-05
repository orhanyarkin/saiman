package io.github.orhanyarkin.saiman.ledger.api;

import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ledger's OpenAPI document, the contract the dashboard's TypeScript client is generated from (ADR-0022,
 * {@code docs/api/ledger.openapi.json}). {@code /v3/api-docs} is off at runtime ({@code springdoc.api-docs.enabled}
 * is false in {@code application.yaml}); the contract test turns it on.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ModelConverter.class)
class OpenApiConfiguration {

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
                        .description(
                                "Double-entry ledger, seller revenue and on-chain reconciliation (testnet only)."));
    }
}
