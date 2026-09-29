package io.github.orhanyarkin.x402.autoconfigure;

import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.facilitator.FacilitatorClient;
import io.github.orhanyarkin.x402.facilitator.HttpFacilitatorClient;
import io.github.orhanyarkin.x402.server.InMemoryPaymentNonceStore;
import io.github.orhanyarkin.x402.server.PaymentNonceStore;
import io.github.orhanyarkin.x402.server.RedisPaymentNonceStore;
import io.github.orhanyarkin.x402.server.RequiresPaymentInterceptor;
import io.github.orhanyarkin.x402.server.RequiresPaymentRegistry;
import io.github.orhanyarkin.x402.server.X402ServerProperties;
import io.github.orhanyarkin.x402.server.X402SettlementFilter;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Auto-configuration for server-side x402 payment enforcement: {@link
 * io.github.orhanyarkin.x402.server.RequiresPayment}, {@link RequiresPaymentInterceptor}, {@link
 * X402SettlementFilter}, a {@link PaymentNonceStore} and a default {@link FacilitatorClient}.
 *
 * <p>Guarded by {@code @ConditionalOnClass(DispatcherServlet.class)}: this whole configuration is
 * inert in an application with no Spring MVC on the classpath. Every bean is also {@code
 * @ConditionalOnMissingBean}, so an application can override any single piece (a custom {@link
 * PaymentNonceStore}, a custom {@link FacilitatorClient} talking to a different facilitator, etc.)
 * without losing the rest.
 *
 * <p>{@code afterName} orders this whole class (by class name only, not a compile-time reference:
 * {@code spring-boot-data-redis} is not a dependency of this starter) after {@code
 * DataRedisAutoConfiguration}. Without it, {@code @ConditionalOnBean(StringRedisTemplate.class)} on
 * the nested Redis configuration below can be evaluated *before* Boot's own Redis auto-configuration
 * has created that bean -- auto-configuration processing order is not the same as "does this bean
 * exist in the final context", and a `@ConditionalOnBean` only ever sees beans already registered
 * by the time it runs. Reproduced and fixed after a probe showed `InMemoryPaymentNonceStore` chosen
 * even with a real `StringRedisTemplate` present.
 *
 * <p>The Redis-backed nonce store and the default HTTP {@link FacilitatorClient} live in their own
 * nested {@code @Configuration} classes, each gated by its own {@code @ConditionalOnClass}: a
 * {@code @Bean} method whose signature references a class that is genuinely absent from the
 * classpath (e.g. {@link StringRedisTemplate} when {@code spring-boot-starter-data-redis} is not
 * used, or {@link RestClient} when an application supplies its own {@link FacilitatorClient} and
 * never adds {@code spring-boot-starter-restclient}) would otherwise fail to load even though its
 * condition never passes -- the JVM resolves a method's parameter and return types when the
 * declaring class is loaded, before Spring evaluates any {@code @Conditional} annotation.
 */
@AutoConfiguration(afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass(DispatcherServlet.class)
@EnableConfigurationProperties(X402ServerProperties.class)
public class X402ServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock x402Clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    X402Codec x402Codec() {
        return new X402Codec();
    }

    @Bean
    @ConditionalOnMissingBean
    RequiresPaymentRegistry x402RequiresPaymentRegistry(
            ObjectProvider<RequestMappingHandlerMapping> requestMappingHandlerMapping,
            ObjectProvider<RequiresPaymentInterceptor> requiresPaymentInterceptor,
            FacilitatorClient facilitatorClient,
            X402ServerProperties properties) {
        return new RequiresPaymentRegistry(
                requestMappingHandlerMapping, requiresPaymentInterceptor, facilitatorClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    RequiresPaymentInterceptor x402RequiresPaymentInterceptor(
            RequiresPaymentRegistry registry,
            X402Codec codec,
            FacilitatorClient facilitatorClient,
            PaymentNonceStore nonceStore,
            X402ServerProperties properties,
            Clock clock) {
        return new RequiresPaymentInterceptor(registry, codec, facilitatorClient, nonceStore, properties, clock);
    }

    /**
     * A {@code @Bean} method parameter, not a {@code WebMvcConfigurer} implemented on this class:
     * {@link RequiresPaymentInterceptor} is itself produced by a {@code @Bean} method on this same
     * class, and a configuration class's own constructor cannot depend on a bean its own {@code
     * @Bean} method produces (the configuration instance must exist before any of its {@code
     * @Bean} methods can run).
     */
    @Bean
    WebMvcConfigurer x402WebMvcConfigurer(RequiresPaymentInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    X402SettlementFilter x402SettlementFilter(
            ObjectProvider<RequestMappingHandlerMapping> requestMappingHandlerMapping,
            RequiresPaymentRegistry registry,
            FacilitatorClient facilitatorClient,
            PaymentNonceStore nonceStore,
            X402Codec codec,
            X402ServerProperties properties,
            ObservationRegistry observationRegistry,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        return new X402SettlementFilter(
                requestMappingHandlerMapping,
                registry,
                facilitatorClient,
                nonceStore,
                codec,
                properties,
                observationRegistry,
                eventPublisher,
                clock);
    }

    /**
     * Registers {@link X402SettlementFilter} explicitly (name + order) rather than relying on
     * Boot's default bare-{@code Filter}-bean auto-registration: a predictable name for
     * diagnostics, and an order low enough (a large value = late in the chain) to run after a
     * typical CORS/security filter, so the header snapshot {@link X402SettlementFilter} takes
     * before dispatching already includes whatever those set.
     */
    @Bean
    FilterRegistrationBean<X402SettlementFilter> x402SettlementFilterRegistration(X402SettlementFilter filter) {
        FilterRegistrationBean<X402SettlementFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("x402SettlementFilter");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(PaymentNonceStore.class)
    PaymentNonceStore x402InMemoryPaymentNonceStore() {
        return new InMemoryPaymentNonceStore();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    static class RedisNonceStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(PaymentNonceStore.class)
        @ConditionalOnBean(StringRedisTemplate.class)
        PaymentNonceStore x402RedisPaymentNonceStore(StringRedisTemplate redisTemplate) {
            return new RedisPaymentNonceStore(redisTemplate);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClient.class)
    static class FacilitatorClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        FacilitatorClient x402FacilitatorClient(
                RestClient.Builder restClientBuilder, X402ServerProperties properties, X402Codec codec) {
            X402ServerProperties.Facilitator facilitator = properties.facilitator();
            return new HttpFacilitatorClient(
                    restClientBuilder,
                    facilitator.url(),
                    facilitator.connectTimeout(),
                    facilitator.readTimeout(),
                    codec);
        }
    }
}
