package io.github.orhanyarkin.saiman.apisecurity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

/**
 * Auto-configuration of the API token verifier (ADR-0023).
 *
 * <p>Unless {@code saiman.auth.enabled} binds to {@code false}, it creates:
 *
 * <ul>
 *   <li>{@link ApiTokenProperties} ({@code saiman.auth.*}), always bound;
 *   <li>a {@link StaticTokenIntrospector}, validated at startup (unless the application defines its own {@link
 *       OpaqueTokenIntrospector});
 *   <li>the {@link RoleHierarchy} {@code ROLE_OPERATOR > ROLE_READER} (unless one is defined), which Spring Security's
 *       {@code authorizeHttpRequests} picks up by type.
 * </ul>
 *
 * <p>"Binds to {@code false}" uses the same conversion as {@link ApiTokenProperties}, so {@code false}, {@code off},
 * {@code no} and {@code 0} all mean disabled ({@link ConditionalOnSaimanAuth}); anything else that is not a boolean
 * fails startup. A disabled configuration starts only with the explicit acknowledgement {@code
 * saiman.auth.allow-disabled-insecure=true} and no digest configured at the same time (a half-configured deployment is
 * refused rather than silently left open); it then creates neither bean and logs a warning. A service's security
 * configuration must use {@link ConditionalOnSaimanAuth}, not {@code @ConditionalOnProperty}, to follow the same rule.
 * No request rules are defined here (see {@link SaimanResourceServer}).
 *
 * <p>Ordered before Boot's security auto-configurations so their {@code @ConditionalOnMissingBean} checks see the
 * introspector (Boot then creates no introspector of its own).
 */
@AutoConfiguration(
        before = {
            OAuth2ResourceServerAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class
        })
@ConditionalOnClass(OpaqueTokenIntrospector.class)
@EnableConfigurationProperties(ApiTokenProperties.class)
public class ApiSecurityAutoConfiguration {

    /** The property switching API authentication on and off. */
    public static final String ENABLED_PROPERTY = "saiman.auth.enabled";

    /** The acknowledgement a disabled configuration needs to start. */
    public static final String ALLOW_DISABLED_PROPERTY = "saiman.auth.allow-disabled-insecure";

    private static final Logger log = LoggerFactory.getLogger(ApiSecurityAutoConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnSaimanAuth(enabled = true)
    static class Enabled {

        @Bean
        @ConditionalOnMissingBean(OpaqueTokenIntrospector.class)
        StaticTokenIntrospector staticTokenIntrospector(ApiTokenProperties properties) {
            return StaticTokenIntrospector.fromProperties(properties);
        }

        /** Static, as Spring Security recommends, so it is available before other configuration is processed. */
        @Bean
        @ConditionalOnMissingBean(RoleHierarchy.class)
        static RoleHierarchy saimanRoleHierarchy() {
            return RoleHierarchyImpl.fromHierarchy(SaimanAuthorities.ROLE_HIERARCHY);
        }
    }

    /** Checks a disabled configuration at startup; never lazy, so the check runs even with lazy initialisation. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnSaimanAuth(enabled = false)
    @Lazy(false)
    static class Disabled {

        Disabled(ApiTokenProperties properties) {
            if (!properties.allowDisabledInsecure()) {
                throw new IllegalStateException(ENABLED_PROPERTY + " is false, which leaves the API without"
                        + " authentication. Set " + ALLOW_DISABLED_PROPERTY + "=true as well to confirm"
                        + " (local experiments only), or remove " + ENABLED_PROPERTY + ".");
            }
            if (properties.hasAnyDigest()) {
                throw new IllegalStateException(ENABLED_PROPERTY + " is false but token digests are configured:"
                        + " refusing a half-configured deployment. Remove " + ENABLED_PROPERTY + " to use the tokens,"
                        + " or remove the digests.");
            }
            log.warn("**********************************************************************************");
            log.warn(
                    "* INSECURE: {}=false and {}=true. Saiman's authentication rules are NOT applied; Boot's default security chain, not Saiman, decides who gets in.",
                    ENABLED_PROPERTY,
                    ALLOW_DISABLED_PROPERTY);
            log.warn("* Never run a deployment reachable by anyone else like this.");
            log.warn("**********************************************************************************");
        }
    }
}
