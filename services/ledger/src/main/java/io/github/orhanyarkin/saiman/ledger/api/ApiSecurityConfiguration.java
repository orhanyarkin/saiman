package io.github.orhanyarkin.saiman.ledger.api;

import io.github.orhanyarkin.saiman.apisecurity.ConditionalOnSaimanAuth;
import io.github.orhanyarkin.saiman.apisecurity.SaimanResourceServer;
import io.github.orhanyarkin.saiman.apisecurity.SaimanRole;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call the ledger's HTTP API (ADR-0023). The authentication mechanism (opaque bearer tokens checked against
 * SHA-256 digests) comes from {@code libs/api-security}; only the request rules live here.
 *
 * <ul>
 *   <li>Reads of the books and reconciliation ({@code GET /api/v1/ledger/**}, {@code GET /api/v1/reconciliation/**})
 *       need READER (an OPERATOR is a READER through the role hierarchy).
 *   <li>Starting a reconciliation run ({@code POST /api/v1/reconciliation/runs}) needs OPERATOR.
 *   <li>{@code GET /actuator/health} and its probes are open (container health checks); {@code /actuator/info} and
 *       the build-time OpenAPI document ({@code /v3/api-docs}, off at runtime) need READER.
 *   <li>Everything else is denied, authenticated or not.
 * </ul>
 *
 * <p>{@link LedgerApiGuardFilter} runs <em>before</em> this chain (its {@code @Order}), so a foreign {@code Host}, a
 * non-canonical path or a missing CSRF header is refused before any token is looked at. With {@code
 * saiman.auth.enabled=false} (and the explicit insecure acknowledgement) this configuration is skipped entirely; there
 * is no permit-all fallback chain.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnSaimanAuth
class ApiSecurityConfiguration {

    @Bean
    SecurityFilterChain ledgerApiSecurity(HttpSecurity http, OpaqueTokenIntrospector introspector) throws Exception {
        String reader = SaimanRole.READER.name();
        String operator = SaimanRole.OPERATOR.name();
        SaimanResourceServer.apply(http, introspector)
                .authorizeHttpRequests(requests -> requests
                        // Error pages re-dispatch the request; the first dispatch was already authorised.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/info")
                        .hasRole(reader)
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**")
                        .hasRole(reader)
                        .requestMatchers(HttpMethod.GET, "/api/v1/ledger/**", "/api/v1/reconciliation/**")
                        .hasRole(reader)
                        .requestMatchers(HttpMethod.POST, "/api/v1/reconciliation/runs")
                        .hasRole(operator)
                        .anyRequest()
                        .denyAll());
        return http.build();
    }
}
