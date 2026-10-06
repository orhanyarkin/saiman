package io.github.orhanyarkin.saiman.dbmigrate;

import java.util.Map;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StringUtils;

/**
 * Runs Flyway as the schema owner and reports an exit code (ADR-0027). A service's {@code main} calls
 * {@link #requested(String[])} first and, when it is {@code true}, exits with {@link #run(String[])} instead of
 * starting the server. The migrator needs only the owner credentials: no app password, Kafka, Redis or provider keys.
 *
 * <p>Configuration is the service's own {@code spring.datasource.url} and {@code spring.flyway.*} (schemas,
 * placeholders, user, password). No {@code DataSource} bean is created: Flyway opens its own connection from
 * {@code spring.flyway.url}, which defaults to {@code ${spring.datasource.url}}.
 */
public final class DbMigrate {

    /** Property (or {@code SAIMAN_RUN_MODE} environment variable) that selects the entry point. */
    public static final String RUN_MODE = "saiman.run-mode";

    private static final String MIGRATE = "migrate";
    private static final Pattern OWNER_ROLE = Pattern.compile("[a-z][a-z0-9_]*_owner");
    private static final Logger LOG = LoggerFactory.getLogger(DbMigrate.class);

    private DbMigrate() {}

    /** {@code true} when {@code SAIMAN_RUN_MODE} or {@code --saiman.run-mode} is {@code migrate}. */
    public static boolean requested(String[] args) {
        return requested(args, new StandardEnvironment());
    }

    static boolean requested(String[] args, ConfigurableEnvironment environment) {
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        return MIGRATE.equalsIgnoreCase(environment.getProperty(RUN_MODE));
    }

    /**
     * Migrates and returns the process exit code: {@code 0} only if Flyway's {@code migrate} succeeded, {@code 1} for
     * any failure, including invalid configuration. Does not call {@code System.exit}; the caller does.
     */
    public static int run(String[] args) {
        try (ConfigurableApplicationContext context = newBuilder().run(args)) {
            return SpringApplication.exit(context);
        } catch (Throwable failure) { // the one-shot must turn every failure into exit code 1
            PreflightException preflight = preflightCause(failure);
            if (preflight != null) {
                // Names properties, never values.
                LOG.error("db-migrate: preflight failed: {}", preflight.getMessage());
            } else {
                // Class name only: Boot's failure report carries the cause, and a message could echo a URL.
                LOG.error("db-migrate: failed ({})", failure.getClass().getSimpleName());
            }
            return 1;
        }
    }

    private static @Nullable PreflightException preflightCause(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PreflightException preflight) {
                return preflight;
            }
        }
        return null;
    }

    /** Invalid configuration found before any connection; distinguishes it from a Flyway failure in the log. */
    private static final class PreflightException extends IllegalStateException {
        PreflightException(String message) {
            super(message);
        }
    }

    static SpringApplicationBuilder newBuilder() {
        return new SpringApplicationBuilder(DbMigrateApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                // Lowest precedence: the service's own application.yaml or an explicit property wins.
                .properties(Map.of("spring.flyway.url", "${spring.datasource.url}"))
                .listeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) DbMigrate::preflight);
    }

    /** Fails before any context or connection exists. Messages name properties, never values. */
    private static void preflight(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();
        if (!"true".equalsIgnoreCase(env.getProperty("spring.flyway.enabled", "true"))) {
            throw new PreflightException("spring.flyway.enabled=false, nothing to run");
        }
        String user = required(env, "spring.flyway.user");
        required(env, "spring.flyway.password");
        required(env, "spring.flyway.url");
        if (!OWNER_ROLE.matcher(user).matches()) {
            throw new PreflightException("spring.flyway.user must be a *_owner role (ADR-0024)");
        }
        String defaultSchema = env.getProperty("spring.flyway.default-schema");
        if (StringUtils.hasText(defaultSchema) && !user.equals(defaultSchema + "_owner")) {
            // Catches a secret mounted from another service.
            throw new PreflightException("spring.flyway.user must be <spring.flyway.default-schema>_owner");
        }
    }

    private static String required(Environment env, String key) {
        String value;
        try {
            value = env.getProperty(key);
        } catch (IllegalArgumentException unresolvable) {
            value = null; // e.g. ${spring.datasource.url} with no datasource url configured
        }
        if (value == null || !StringUtils.hasText(value)) {
            throw new PreflightException(key + " must be set");
        }
        return value;
    }

    /** Runs {@code migrate} in place of Boot's default initializer so the result can be logged. */
    static FlywayMigrationStrategy loggingStrategy(Environment environment) {
        return flyway -> {
            long start = System.nanoTime();
            MigrateResult result = flyway.migrate();
            long millis = (System.nanoTime() - start) / 1_000_000;
            if (!result.success) {
                throw new IllegalStateException("db-migrate: Flyway reported an unsuccessful migrate");
            }
            LOG.info(
                    "db-migrate: schema={} user={} from={} to={} applied={} durationMs={}",
                    schemaOf(flyway),
                    environment.getProperty("spring.flyway.user"), // the pool-less connection takes it from properties
                    versionOrNone(result.initialSchemaVersion),
                    versionOrNone(currentVersion(flyway)),
                    result.migrationsExecuted,
                    millis);
        };
    }

    private static String schemaOf(Flyway flyway) {
        String defaultSchema = flyway.getConfiguration().getDefaultSchema();
        if (StringUtils.hasText(defaultSchema)) {
            return defaultSchema;
        }
        String[] schemas = flyway.getConfiguration().getSchemas();
        return schemas.length > 0 ? String.join(",", schemas) : "default";
    }

    private static @Nullable String currentVersion(Flyway flyway) {
        // A second, short connection: MigrateResult has no final version when nothing was applied.
        MigrationInfo current = flyway.info().current();
        return current == null || current.getVersion() == null
                ? null
                : current.getVersion().getVersion();
    }

    private static String versionOrNone(@Nullable String version) {
        return version != null && StringUtils.hasText(version) ? version : "none";
    }
}
