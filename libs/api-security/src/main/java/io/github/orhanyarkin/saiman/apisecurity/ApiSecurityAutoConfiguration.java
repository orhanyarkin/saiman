package io.github.orhanyarkin.saiman.apisecurity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

/**
 * Auto-configuration of the API token verifier (ADR-0023). With {@code saiman.auth.enabled=true} (the default) it
 * creates:
 *
 * <ul>
 *   <li>{@link ApiTokenProperties} ({@code saiman.auth.*}), always bound so a service can read {@code enabled()};
 *   <li>a {@link StaticTokenIntrospector}, validated at startup (unless the application defines its own {@link
 *       OpaqueTokenIntrospector});
 *   <li>the {@link RoleHierarchy} {@code ROLE_OPERATOR > ROLE_READER} (unless one is defined), which Spring Security's
 *       {@code authorizeHttpRequests} picks up by type.
 * </ul>
 *
 * <p>With {@code saiman.auth.enabled=false} it creates neither bean and logs a warning; a service's security
 * configuration must be conditional on the same property. No request rules are defined here (see {@link
 * SaimanResourceServer}).
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

    private static final Logger log = LoggerFactory.getLogger(ApiSecurityAutoConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
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

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "false")
    static class Disabled {

        Disabled() {
            log.warn("saiman.auth.enabled=false: no API token verifier is configured. Never run a reachable"
                    + " deployment like this.");
        }
    }
}
