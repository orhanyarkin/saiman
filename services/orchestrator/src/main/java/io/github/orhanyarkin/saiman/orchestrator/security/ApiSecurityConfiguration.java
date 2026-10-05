package io.github.orhanyarkin.saiman.orchestrator.security;

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
 * Request rules of the orchestrator API (ADR-0023). How a token is verified is {@link SaimanResourceServer}'s business;
 * this class only says who may call what, and ends with {@code denyAll()}. There is no second, permissive chain, and
 * with {@code saiman.auth.enabled=false} no chain is defined at all ({@link ConditionalOnSaimanAuth}).
 *
 * <ul>
 *   <li>{@code GET /actuator/health/**}: open (container health checks).
 *   <li>ASYNC and ERROR dispatches: open. An SSE stream is re-dispatched when its emitter completes, and Boot's error
 *       page is a forward; the original dispatch was already authorised.
 *   <li>{@code GET /api/v1/**} (runs, the SSE stream, payments, approvals, spend, {@code /me}): READER.
 *   <li>{@code POST /api/v1/runs} and {@code POST /api/v1/runs/{id}/approvals/{id}}: OPERATOR (which includes READER
 *       through the role hierarchy).
 *   <li>Everything else: denied.
 * </ul>
 *
 * <p>{@code ApiRequestGuardFilter} (Host allowlist, canonical path, CSRF header, body size) runs before this chain.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnSaimanAuth
class ApiSecurityConfiguration {

    @Bean
    SecurityFilterChain orchestratorApiSecurity(HttpSecurity http, OpaqueTokenIntrospector introspector)
            throws Exception {
        SaimanResourceServer.apply(http, introspector)
                .authorizeHttpRequests(
                        requests -> requests.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                                .permitAll()
                                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                                .permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/v1/**")
                                .hasRole(SaimanRole.READER.name())
                                .requestMatchers(HttpMethod.POST, "/api/v1/runs", "/api/v1/runs/*/approvals/*")
                                .hasRole(SaimanRole.OPERATOR.name())
                                .anyRequest()
                                .denyAll());
        return http.build();
    }
}
