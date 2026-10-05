package io.github.orhanyarkin.saiman.apisecurity.sample;

import io.github.orhanyarkin.saiman.apisecurity.SaimanAuthorities;
import io.github.orhanyarkin.saiman.apisecurity.SaimanResourceServer;
import io.github.orhanyarkin.saiman.apisecurity.SaimanRole;
import jakarta.servlet.DispatcherType;
import java.security.Principal;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A test-only service: what a service's {@code ApiSecurityConfiguration} looks like (the same shape as the example in
 * {@link SaimanResourceServer}'s javadoc), with a controller that uses no security API except the principal name.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({SampleApplication.SampleApiSecurityConfiguration.class, SampleApplication.SampleController.class})
public class SampleApplication {

    @Configuration(proxyBeanMethods = false)
    static class SampleApiSecurityConfiguration {

        @Bean
        SecurityFilterChain apiSecurity(HttpSecurity http, OpaqueTokenIntrospector introspector) throws Exception {
            SaimanResourceServer.apply(http, introspector)
                    .authorizeHttpRequests(
                            requests -> requests.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                                    .permitAll()
                                    .requestMatchers(HttpMethod.GET, "/actuator/health")
                                    .permitAll()
                                    .requestMatchers(HttpMethod.GET, "/api/v1/**")
                                    .hasRole(SaimanRole.READER.name())
                                    .requestMatchers(HttpMethod.POST, "/api/v1/runs")
                                    .hasRole(SaimanRole.OPERATOR.name())
                                    .requestMatchers("/internal/credit-notes/**")
                                    .hasAuthority(SaimanAuthorities.service("ledger"))
                                    .anyRequest()
                                    .denyAll());
            return http.build();
        }
    }

    @RestController
    static class SampleController {

        @GetMapping("/api/v1/runs")
        String listRuns(Principal principal) {
            return "runs for " + principal.getName();
        }

        @PostMapping("/api/v1/runs")
        String startRun(Principal principal) {
            return "started by " + principal.getName();
        }

        @GetMapping("/internal/credit-notes/latest")
        String creditNote(Principal principal) {
            return "credit note for " + principal.getName();
        }

        @GetMapping("/api/v2/unmapped-by-rules")
        String notInTheRules() {
            return "never served";
        }
    }
}
