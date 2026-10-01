package io.github.orhanyarkin.saiman.eventing;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Adds ADR-0016's Spring Modulith event-registry defaults as the lowest-precedence property source, so
 * anything the application, its profile files, the environment or the command line sets wins.
 *
 * <ul>
 *   <li>{@code registry-trigger-annotation}: only {@code @ApplicationModuleListener} methods are persisted in the
 *       registry; plain {@code @TransactionalEventListener}s (in-process fan-out) stay out of it. The value is a
 *       fully-qualified class name (checked against Modulith 2.1.1: it is resolved as a class and must be an
 *       annotation).
 *   <li>{@code republish-outstanding-events-on-restart}: incomplete publications are retried after a crash.
 *   <li>{@code completion-mode=DELETE}: completed publications are removed, so the registry table stays small
 *       without a purge job.
 * </ul>
 */
public class ModulithDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "saimanEventingDefaults";

    static final Map<String, Object> DEFAULTS = Map.of(
            "spring.modulith.events.registry-trigger-annotation",
            "org.springframework.modulith.events.ApplicationModuleListener",
            "spring.modulith.events.republish-outstanding-events-on-restart",
            "true",
            "spring.modulith.events.completion-mode",
            "DELETE");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getPropertySources().contains(SOURCE_NAME)) {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, DEFAULTS));
        }
    }
}
