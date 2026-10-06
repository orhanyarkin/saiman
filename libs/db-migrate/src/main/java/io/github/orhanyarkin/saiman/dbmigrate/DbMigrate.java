package io.github.orhanyarkin.saiman.dbmigrate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.springframework.boot.context.properties.bind.Binder;
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
    private static final String SERVER = "server";
    private static final Set<String> FORBIDDEN_URL_PARAMS = Set.of("user", "password", "service", "passfile");
    private static final Pattern OWNER_ROLE = Pattern.compile("[a-z][a-z0-9_]*_owner");
    private static final Logger LOG = LoggerFactory.getLogger(DbMigrate.class);

    private DbMigrate() {}

    /**
     * Reads {@code SAIMAN_RUN_MODE} or {@code --saiman.run-mode}. Absent, blank or {@code server} gives
     * {@code false}; {@code migrate} gives {@code true} (both case-insensitive, trimmed).
     *
     * @throws IllegalArgumentException for any other value, so a typo such as {@code migrat} can never start the
     *     server by accident
     */
    public static boolean requested(String[] args) {
        return requested(args, new StandardEnvironment());
    }

    static boolean requested(String[] args, ConfigurableEnvironment environment) {
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        String mode = environment.getProperty(RUN_MODE);
        String value = mode == null ? "" : mode.trim();
        if (value.isEmpty() || SERVER.equalsIgnoreCase(value)) {
            return false;
        }
        if (MIGRATE.equalsIgnoreCase(value)) {
            return true;
        }
        throw new IllegalArgumentException(RUN_MODE + " must be 'server' or 'migrate'");
    }

    /**
     * Migrates and returns the process exit code: {@code 0} only if Flyway's {@code migrate} succeeded, {@code 1} for
     * any failure, including invalid configuration. Does not call {@code System.exit}; the caller does.
     */
    public static int run(String[] args) {
        try (ConfigurableApplicationContext context = newBuilder().run(args)) {
            // Read before exit(): exit closes the context.
            MigrationOutcome outcome =
                    context.getBeanProvider(MigrationOutcome.class).getIfAvailable();
            int code = SpringApplication.exit(context);
            if (outcome == null || !outcome.completed()) {
                LOG.error("db-migrate: failed (migrate did not run)");
                return 1;
            }
            return code;
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
        if ("true".equalsIgnoreCase(env.getProperty("spring.main.lazy-initialization", "false"))) {
            throw new PreflightException("spring.main.lazy-initialization must not be true");
        }
        // Only Flyway's own exclusion matters: services' test yaml legitimately excludes other auto-configurations.
        if (Binder.get(env)
                .bind("spring.autoconfigure.exclude", String[].class)
                .map(excluded ->
                        Arrays.stream(excluded).anyMatch(name -> name.trim().endsWith("FlywayAutoConfiguration")))
                .orElse(false)) {
            throw new PreflightException("spring.autoconfigure.exclude must not exclude FlywayAutoConfiguration");
        }
        String user = required(env, "spring.flyway.user");
        required(env, "spring.flyway.password");
        checkUrl(required(env, "spring.flyway.url"));
        if (!OWNER_ROLE.matcher(user).matches()) {
            throw new PreflightException("spring.flyway.user must be a *_owner role (ADR-0024)");
        }
        String service = user.substring(0, user.length() - "_owner".length());
        String defaultSchema = env.getProperty("spring.flyway.default-schema");
        String[] schemas =
                Binder.get(env).bind("spring.flyway.schemas", String[].class).orElse(new String[0]);
        if (!StringUtils.hasText(defaultSchema) && schemas.length == 0) {
            throw new PreflightException("spring.flyway.default-schema or spring.flyway.schemas must be set");
        }
        // Catches a secret mounted from another service.
        if (StringUtils.hasText(defaultSchema) && !service.equals(defaultSchema)) {
            throw new PreflightException("spring.flyway.user must be <spring.flyway.default-schema>_owner");
        }
        for (String schema : schemas) {
            if (!service.equals(schema)) {
                throw new PreflightException("spring.flyway.user must be <each spring.flyway.schemas entry>_owner");
            }
        }
    }

    /** pgjdbc lets URL query parameters override the Properties user and password, so none may carry them. */
    private static void checkUrl(String url) {
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new PreflightException("spring.flyway.url must be a jdbc:postgresql: URL");
        }
        int query = url.indexOf('?');
        if (query < 0) {
            return;
        }
        for (String pair : StringUtils.tokenizeToStringArray(url.substring(query + 1), "&;")) {
            String key = pair.split("=", 2)[0];
            try {
                key = URLDecoder.decode(key, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                throw new PreflightException("spring.flyway.url has a malformed query");
            }
            if (FORBIDDEN_URL_PARAMS.contains(key.trim().toLowerCase(Locale.ROOT))) {
                throw new PreflightException("spring.flyway.url must not carry user, password, service or passfile");
            }
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
    static FlywayMigrationStrategy loggingStrategy(Environment environment, MigrationOutcome outcome) {
        return flyway -> {
            String user = verifySession(flyway, environment);
            long start = System.nanoTime();
            MigrateResult result = flyway.migrate();
            long millis = (System.nanoTime() - start) / 1_000_000;
            if (!result.success) {
                throw new IllegalStateException("db-migrate: Flyway reported an unsuccessful migrate");
            }
            LOG.info(
                    "db-migrate: schema={} user={} from={} to={} applied={} durationMs={}",
                    schemaOf(flyway),
                    user, // as the database reports it, not as configured
                    versionOrNone(result.initialSchemaVersion),
                    versionOrNone(currentVersion(flyway)),
                    result.migrationsExecuted,
                    millis);
            outcome.markCompleted();
        };
    }

    /**
     * Opens one connection the way Flyway will and asks the database who we are. Returns {@code current_user}. Fails
     * unless it is the configured owner, with no session switch, no superuser and no BYPASSRLS, and the schema, if it
     * exists, is owned by it.
     */
    private static String verifySession(Flyway flyway, Environment environment) {
        String expected = environment.getProperty("spring.flyway.user");
        String schema = StringUtils.tokenizeToStringArray(schemaOf(flyway), ",")[0];
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT current_user, session_user, r.rolsuper, r.rolbypassrls,
                               (SELECT pg_get_userbyid(n.nspowner) FROM pg_namespace n WHERE n.nspname = ?)
                        FROM pg_roles r WHERE r.rolname = current_user""")) {
            statement.setString(1, schema);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new PreflightException("current role is not visible in pg_roles");
                }
                String currentUser = row.getString(1);
                if (!currentUser.equals(expected) || !currentUser.equals(row.getString(2))) {
                    throw new PreflightException("connected role differs from spring.flyway.user");
                }
                if (row.getBoolean(3) || row.getBoolean(4)) {
                    throw new PreflightException("the owner role must not be SUPERUSER or BYPASSRLS");
                }
                String schemaOwner = row.getString(5);
                if (schemaOwner != null && !schemaOwner.equals(currentUser)) {
                    throw new PreflightException("the schema is not owned by the connected role");
                }
                return currentUser;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("db-migrate: session check failed", e);
        }
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
