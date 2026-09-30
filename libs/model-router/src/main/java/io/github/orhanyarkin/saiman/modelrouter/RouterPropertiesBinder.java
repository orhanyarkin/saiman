package io.github.orhanyarkin.saiman.modelrouter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Why this class exists: the library's defaults ({@code config/router/*.yaml}) must sit below the
 * application's own {@code saiman.router.*} properties, and an auto-configuration cannot register a
 * lowest-priority property source. Composing a {@link Binder} here keeps the library self-contained
 * (no {@code EnvironmentPostProcessor}, no {@code spring.factories}) and works the same in an
 * {@code ApplicationContextRunner}.
 *
 * <p>Binds {@link RouterProperties} from the application's environment with the library's classpath
 * defaults ({@code config/router/routes.yaml}, {@code prices.yaml}) as the lowest-priority
 * sources. Registering the defaults as ordinary property sources is not possible from an
 * auto-configuration, so the binder is composed here: environment sources first (they win), then
 * the defaults.
 */
final class RouterPropertiesBinder {

    static final String OPENAI_KEY_PROPERTY = "openai_api_key";

    private static final List<String> DEFAULT_RESOURCES =
            List.of("config/router/routes.yaml", "config/router/prices.yaml");

    private RouterPropertiesBinder() {}

    static RouterProperties bind(Environment environment) {
        List<ConfigurationPropertySource> sources = new ArrayList<>();
        ConfigurationPropertySources.get(environment).forEach(sources::add);
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String location : DEFAULT_RESOURCES) {
            Resource resource = new ClassPathResource(location);
            try {
                for (PropertySource<?> source : loader.load("saiman-router-defaults:" + location, resource)) {
                    ConfigurationPropertySources.from(source).forEach(sources::add);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read model router defaults " + location, e);
            }
        }
        Binder binder = new Binder(sources, new PropertySourcesPlaceholdersResolver(environment));
        RouterProperties bound = binder.bind("saiman.router", Bindable.of(RouterProperties.class))
                .orElseThrow(() -> new IllegalStateException("saiman.router defaults are missing"));
        if (!bound.openai().hasApiKey()) {
            String fromConfigTree = environment.getProperty(OPENAI_KEY_PROPERTY);
            if (fromConfigTree != null && !fromConfigTree.isBlank()) {
                return new RouterProperties(
                        bound.routes(),
                        bound.embedding(),
                        bound.dailyCapUsdMicros(),
                        bound.prices(),
                        new RouterProperties.OpenAi(fromConfigTree));
            }
        }
        return bound;
    }
}
