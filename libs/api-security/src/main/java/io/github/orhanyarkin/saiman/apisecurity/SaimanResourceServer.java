package io.github.orhanyarkin.saiman.apisecurity;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * The one place the API authentication mechanism is chosen (ADR-0023): an OAuth2 resource server with opaque bearer
 * tokens, checked by the given introspector. Request rules are <em>not</em> here; each service declares them in its own
 * {@code ApiSecurityConfiguration}:
 *
 * <pre>{@code
 * @Configuration(proxyBeanMethods = false)
 * @ConditionalOnSaimanAuth
 * class ApiSecurityConfiguration {
 *
 *     @Bean
 *     SecurityFilterChain apiSecurity(HttpSecurity http, OpaqueTokenIntrospector introspector) throws Exception {
 *         SaimanResourceServer.apply(http, introspector)
 *                 .authorizeHttpRequests(requests -> requests
 *                         // SSE and error pages re-dispatch the request; the first dispatch was already authorised.
 *                         .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
 *                         .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
 *                         .requestMatchers(HttpMethod.GET, "/api/v1/**").hasRole(SaimanRole.READER.name())
 *                         .requestMatchers(HttpMethod.POST, "/api/v1/runs").hasRole(SaimanRole.OPERATOR.name())
 *                         .anyRequest().denyAll());
 *         return http.build();
 *     }
 * }
 * }</pre>
 *
 * <p>The {@code RoleHierarchy} bean of the auto-configuration makes {@code hasRole("READER")} true for an OPERATOR.
 *
 * <p><b>Rules every service must follow:</b>
 *
 * <ul>
 *   <li>A service route checks the <em>caller</em>: {@code .requestMatchers("/internal/credit-notes/**")
 *       .hasAuthority(SaimanAuthorities.service("ledger"))}. Never {@code hasRole("SERVICE")} or {@code
 *       authenticated()}: those let every service token (and, for {@code authenticated()}, every human token) in.
 *   <li>End with {@code anyRequest().denyAll()}, and never add a second {@code SecurityFilterChain} that permits
 *       everything as a fallback, not even for {@code saiman.auth.enabled=false}: with authentication disabled the
 *       service defines no chain at all (its configuration is {@code @ConditionalOnSaimanAuth}), and the
 *       acknowledgement {@code saiman.auth.allow-disabled-insecure=true} is the only way to get there.
 *   <li>Code that holds the authenticated {@code BearerTokenAuthentication} must not log {@code getToken()} or {@code
 *       getCredentials()}: they return the raw token. Read the principal name only.
 * </ul>
 *
 * <p>What {@link #apply} sets:
 *
 * <ul>
 *   <li>Stateless: no HTTP session, no saved request; nothing sets a cookie.
 *   <li>CSRF protection off: credentials are a bearer header, never a cookie, so a cross-site page cannot send them.
 *       The services' own {@code X-Saiman-Csrf} guard filters stay as defence in depth.
 *   <li>No form login, HTTP Basic or logout endpoints.
 *   <li>The token is read from the {@code Authorization: Bearer} header only; {@code access_token} query and form
 *       parameters are ignored (a token in a URL ends up in access logs and browser history). The authentication
 *       request built from it ({@link RedactingBearerTokenConverter}) returns {@code [PROTECTED]} as principal, name,
 *       credentials and string form, unlike Spring's {@code BearerTokenAuthenticationToken} whose {@code getName()}
 *       is the raw token.
 *   <li>401 and 403 answers are fixed RFC 9457 bodies ({@link ProblemAuthenticationEntryPoint}, {@link
 *       ProblemAccessDeniedHandler}), for both the missing-token path (exception translation) and the bad-token path
 *       (the bearer filter's failure handler).
 * </ul>
 *
 * <p>Upgrade path to OIDC: replace {@code opaqueToken(...)} below with {@code jwt(...)} and a converter that maps a
 * {@code roles} claim to the same authorities; nothing else changes.
 */
public final class SaimanResourceServer {

    private SaimanResourceServer() {}

    /**
     * Applies the authentication mechanism to {@code http} and returns it, so request rules can be chained.
     *
     * @param http the service's {@code HttpSecurity}
     * @param introspector the token verifier, normally the auto-configured {@link StaticTokenIntrospector}
     * @return {@code http}
     * @throws Exception as declared by Spring Security's configurers
     */
    public static HttpSecurity apply(HttpSecurity http, OpaqueTokenIntrospector introspector) throws Exception {
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint();
        ProblemAccessDeniedHandler accessDenied = new ProblemAccessDeniedHandler();
        // Header-only bearer resolution; the unauthenticated token never exposes the raw value as its principal.
        RedactingBearerTokenConverter headerOnly = new RedactingBearerTokenConverter();

        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationConverter(headerOnly)
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied)
                        .opaqueToken(opaque -> opaque.introspector(introspector)));
    }
}
