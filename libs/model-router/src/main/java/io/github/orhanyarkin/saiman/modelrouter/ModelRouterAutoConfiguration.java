package io.github.orhanyarkin.saiman.modelrouter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Auto-configuration of the model router (ADR-0011).
 *
 * <ul>
 *   <li>The router exists even without an OpenAI key; the first call then fails closed.
 *   <li>The daily cap lives in Valkey when a {@code StringRedisTemplate} bean exists, otherwise in
 *       memory (with a startup warning): that guard is per process and does not protect a shared
 *       budget.
 *   <li>Meters are registered only when a {@link MeterRegistry} bean exists.
 * </ul>
 *
 * Ordered after Boot's Redis and metrics auto-configurations so that the {@code @ConditionalOnBean}
 * conditions see their beans.
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration"
        })
public class ModelRouterAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelRouterAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(RouterProperties.class)
    RouterProperties routerProperties(Environment environment) {
        warnAboutRiskyEnvironment(environment);
        return RouterPropertiesBinder.bind(environment);
    }

    @Bean
    @ConditionalOnMissingBean(ModelFactory.class)
    ModelFactory openAiModelFactory(RouterProperties properties) {
        return new OpenAiModelFactory(properties.openai());
    }

    @Bean
    @ConditionalOnMissingBean(ModelRouter.class)
    ModelRouter modelRouter(
            RouterProperties properties,
            ModelFactory factory,
            CostGuard costGuard,
            RouterMetrics metrics,
            ObjectProvider<ScopedCostGuard> scopedGuard,
            ObjectProvider<ObservationRegistry> observations) {
        return new DefaultModelRouter(
                properties,
                factory,
                costGuard,
                metrics,
                scopedGuard.getIfAvailable(),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    static class ValkeyCostGuardConfiguration {

        @Bean
        @ConditionalOnMissingBean(CostGuard.class)
        @ConditionalOnBean(StringRedisTemplate.class)
        CostGuard valkeyCostGuard(
                StringRedisTemplate redisTemplate, RouterProperties properties, ObjectProvider<Clock> clock) {
            return new ValkeyCostGuard(
                    redisTemplate, properties.dailyCapUsdMicros(), clock.getIfAvailable(Clock::systemUTC));
        }

        @Bean
        @ConditionalOnMissingBean(ScopedCostGuard.class)
        @ConditionalOnBean(StringRedisTemplate.class)
        ScopedCostGuard valkeyScopedCostGuard(StringRedisTemplate redisTemplate) {
            return new ValkeyScopedCostGuard(redisTemplate);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MicrometerMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(RouterMetrics.class)
        @ConditionalOnBean(MeterRegistry.class)
        RouterMetrics micrometerRouterMetrics(MeterRegistry registry) {
            return new MicrometerRouterMetrics(registry);
        }
    }

    @Bean
    @ConditionalOnMissingBean(CostGuard.class)
    CostGuard inMemoryCostGuard(RouterProperties properties, ObjectProvider<Clock> clock) {
        if (!"memory".equals(properties.costGuard())) {
            throw new IllegalStateException("No StringRedisTemplate bean, so the model router has no shared daily"
                    + " cost cap. Configure Valkey (spring.data.redis.*), or set saiman.router.cost-guard=memory"
                    + " to accept a per-process cap (tests, single-process demos only).");
        }
        log.warn("saiman.router.cost-guard=memory: the daily cost cap is counted in memory, per process,"
                + " and is not shared between services.");
        return new InMemoryCostGuard(properties.dailyCapUsdMicros(), clock.getIfAvailable(Clock::systemUTC));
    }

    /** Per-process run budgets, only together with the per-process daily guard (tests, demos). */
    @Bean
    @ConditionalOnMissingBean(ScopedCostGuard.class)
    @ConditionalOnProperty(name = "saiman.router.cost-guard", havingValue = "memory")
    ScopedCostGuard inMemoryScopedCostGuard() {
        return new InMemoryScopedCostGuard();
    }

    @Bean
    @ConditionalOnMissingBean(RouterMetrics.class)
    RouterMetrics noopRouterMetrics() {
        return RouterMetrics.NOOP;
    }

    /**
     * Warns (variable names only, never values) about environment variables that change what the
     * OpenAI SDK does: {@code OPENAI_LOG=debug} dumps request bodies with prompts, and the base-URL
     * variables are ignored by this router but signal a misconfigured deployment.
     */
    static void warnAboutRiskyEnvironment(Environment environment) {
        String sdkLog = environment.getProperty("OPENAI_LOG");
        if (sdkLog != null && !sdkLog.isBlank()) {
            log.warn("OPENAI_LOG is set: the OpenAI SDK may log request bodies (prompts)."
                    + " Unset it outside local debugging.");
        }
        for (String name : new String[] {"OPENAI_BASE_URL", "AZURE_OPENAI_BASE_URL"}) {
            String value = environment.getProperty(name);
            if (value != null && !value.isBlank()) {
                log.warn(
                        "{} is set but ignored: the model router always talks to {}.",
                        name,
                        OpenAiModelFactory.BASE_URL);
            }
        }
    }
}
