package io.github.orhanyarkin.x402.autoconfigure;

import io.github.orhanyarkin.x402.observation.X402ObservationFilterRegistrar;
import io.github.orhanyarkin.x402.observation.X402PaymentMetricsListener;
import io.github.orhanyarkin.x402.server.RequiresPaymentRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for x402 observability: registers {@code X402RedactingObservationFilter} on
 * the application's {@link ObservationRegistry} so payment payloads and signatures never reach a
 * trace exporter or metrics backend (ADR-0006 amendment), and -- when {@code
 * io.micrometer:micrometer-core} is on the classpath -- the {@code x402.payments} counter and
 * {@code x402.payment.amount} distribution summary.
 *
 * <p>Guarded by {@code @ConditionalOnClass(ObservationRegistry.class)} ({@code
 * io.micrometer:micrometer-observation}, transitively required by Spring Framework's own HTTP
 * client/server observation support since Spring Framework 6/Boot 3 -- present whenever Spring MVC
 * or {@code RestClient} is). A fallback {@link ObservationRegistry} bean is only created ({@code
 * @ConditionalOnMissingBean}) if the application does not already have one (e.g. from {@code
 * spring-boot-starter-actuator}, whose {@code spring-boot-micrometer-observation} module registers
 * its own real, tracing/metrics-wired {@code ObservationRegistry}); in that case this starter's
 * redaction still applies to whichever registry ends up in the context. {@code afterName} orders
 * this configuration after that module's {@code ObservationAutoConfiguration} by class name only
 * (a string, not a compile-time type reference): {@code spring-boot-micrometer-observation} is not
 * a dependency of this starter, so the class may not even be on a given application's classpath,
 * but when it is, its {@code @ConditionalOnMissingBean} must be evaluated -- and therefore its own
 * registry bean registered -- before this starter's fallback runs, or the two would race and the
 * loser's {@code ObservationRegistry} (whichever has no tracing/metrics handlers attached) would
 * silently receive no spans or meters at all.
 *
 * <p>The metrics listener lives in its own nested {@code @ConditionalOnClass(MeterRegistry.class)}
 * configuration, for the same JVM class-loading reason {@code X402ServerAutoConfiguration}'s
 * Redis/RestClient nested configurations do: {@code io.micrometer.core.instrument.MeterRegistry}
 * is {@code compileOnly} on this starter (declared in {@code build.gradle.kts} specifically so
 * this metrics support could be added), so a {@code @Bean} method referencing it directly must
 * live on a class that is never loaded unless that condition already passed.
 */
@AutoConfiguration(
        afterName = "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration")
@ConditionalOnClass(ObservationRegistry.class)
public class X402ObservationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObservationRegistry x402ObservationRegistry() {
        return ObservationRegistry.create();
    }

    @Bean
    X402ObservationFilterRegistrar x402ObservationFilterRegistrar(
            ObservationRegistry observationRegistry, ObjectProvider<RequiresPaymentRegistry> requiresPaymentRegistry) {
        return new X402ObservationFilterRegistrar(observationRegistry, requiresPaymentRegistry);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        X402PaymentMetricsListener x402PaymentMetricsListener(ObjectProvider<MeterRegistry> meterRegistry) {
            return new X402PaymentMetricsListener(meterRegistry);
        }
    }
}
