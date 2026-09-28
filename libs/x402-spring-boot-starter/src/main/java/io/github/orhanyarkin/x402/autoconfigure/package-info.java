/**
 * Spring Boot auto-configuration for the x402 starter, split by concern.
 *
 * <p>{@link io.github.orhanyarkin.x402.autoconfigure.X402ServerAutoConfiguration},
 * {@link io.github.orhanyarkin.x402.autoconfigure.X402ClientAutoConfiguration} and
 * {@link io.github.orhanyarkin.x402.autoconfigure.X402ObservationAutoConfiguration} are
 * registered in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * so any application depending on this starter picks them up automatically. They are empty
 * placeholders until the {@code server}, {@code client} and {@code facilitator} packages land
 * (M1 tasks T2/T3): {@code @RequiresPayment}, the settlement filter, a default {@code
 * FacilitatorClient}, the {@code RestClient} payment interceptor and {@code SpendGuard}.
 */
@NullMarked
package io.github.orhanyarkin.x402.autoconfigure;

import org.jspecify.annotations.NullMarked;
