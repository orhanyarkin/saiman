package io.github.orhanyarkin.saiman.sellerapi.security;

import io.github.orhanyarkin.saiman.apisecurity.ConditionalOnSaimanAuth;
import io.github.orhanyarkin.saiman.apisecurity.SaimanAuthorities;
import io.github.orhanyarkin.saiman.apisecurity.SaimanResourceServer;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;

/**
 * seller-api's only security filter chain (ADR-0023): service tokens on {@code /internal/**}, nothing else.
 *
 * <p><b>Scope.</b> The chain's {@code securityMatcher} is {@value #INTERNAL}, so the paid {@code /v1/**} endpoints
 * match no chain: none of its filters (authentication, exception translation, security headers) run there, x402 is
 * their authentication, and the starter's filter reads and writes the {@code PAYMENT-*} headers exactly as before.
 * Spring Security's {@code FilterChainProxy} still applies its {@code StrictHttpFirewall} to <em>every</em> request,
 * so a paid path with a {@code ;} parameter or a double slash ({@code /v1/x;y}, {@code /v1//x}) is now refused with
 * 400 before x402 sees it (no claim, no {@code /verify}). Because this application defines a {@code
 * SecurityFilterChain}, Boot creates no default chain for the other paths.
 *
 * <p><b>Rules.</b> Each internal route checks its <em>caller</em>, never just "some service":
 *
 * <ul>
 *   <li>{@code GET /internal/credit-notes/**}: the ledger's corroboration read, {@code SERVICE_ledger} only;
 *   <li>{@code POST /internal/v1/eval/**}: the eval harness (ADR-0025), {@code SERVICE_evals} only;
 *   <li>any other method or path under {@code /internal}: denied, whoever asks.
 * </ul>
 *
 * The handlers' own {@code Host} allowlist ({@code seller.internal.allowed-hosts}) still runs after authentication.
 *
 * <p>With {@code saiman.auth.enabled=false} (local experiments, explicitly acknowledged) this configuration is absent:
 * there is then no seller chain at all and Spring Boot's default chain applies to every path.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnSaimanAuth
class ApiSecurityConfiguration {

    static final String INTERNAL = "/internal/**";

    @Bean
    SecurityFilterChain internalApiSecurity(HttpSecurity http, OpaqueTokenIntrospector introspector) throws Exception {
        SaimanResourceServer.apply(http, introspector)
                .securityMatcher(INTERNAL)
                .authorizeHttpRequests(requests -> requests
                        // Error pages re-dispatch the request; the first dispatch was already authorised.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/internal/credit-notes/**")
                        .hasAuthority(SaimanAuthorities.service("ledger"))
                        .requestMatchers(HttpMethod.POST, "/internal/v1/eval/**")
                        .hasAuthority(SaimanAuthorities.service("evals"))
                        .anyRequest()
                        .denyAll());
        return http.build();
    }
}
