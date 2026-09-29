package io.github.orhanyarkin.x402.observation;

import io.github.orhanyarkin.x402.server.RequiresPaymentRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Registers {@link X402RedactingObservationFilter} on the application's {@link ObservationRegistry}
 * at startup.
 *
 * <p>An {@link InitializingBean} rather than a {@code @Bean}-returned filter instance: {@link
 * ObservationRegistry} has no Spring Boot auto-configuration hook (comparable to a Micrometer
 * {@code MeterRegistryCustomizer}) for contributing an {@link io.micrometer.observation.ObservationFilter}
 * declaratively in this starter's dependency footprint (it depends only on {@code
 * io.micrometer:micrometer-observation}, not the full {@code micrometer-core}/actuator stack), so
 * this bean mutates the registry directly once, during context startup.
 *
 * <p>{@link RequiresPaymentRegistry} is passed through as an {@link ObjectProvider}: it may not
 * exist at all (a client-only application), and even when it does, this bean is created well
 * before {@link RequiresPaymentRegistry}'s own {@code SmartInitializingSingleton} callback has run
 * (which is when it actually knows whether any {@code @RequiresPayment} handler exists) --
 * {@link X402RedactingObservationFilter} defers that lookup to every {@code map} call instead.
 */
public final class X402ObservationFilterRegistrar implements InitializingBean {

    private final ObservationRegistry registry;
    private final ObjectProvider<RequiresPaymentRegistry> requiresPaymentRegistry;

    public X402ObservationFilterRegistrar(
            ObservationRegistry registry, ObjectProvider<RequiresPaymentRegistry> requiresPaymentRegistry) {
        this.registry = registry;
        this.requiresPaymentRegistry = requiresPaymentRegistry;
    }

    @Override
    public void afterPropertiesSet() {
        registry.observationConfig().observationFilter(new X402RedactingObservationFilter(requiresPaymentRegistry));
    }
}
