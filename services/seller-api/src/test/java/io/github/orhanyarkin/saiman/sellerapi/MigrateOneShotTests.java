package io.github.orhanyarkin.saiman.sellerapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.dbmigrate.DbMigrate;
import io.github.orhanyarkin.saiman.testsupport.SharedContainers;
import io.github.orhanyarkin.saiman.testsupport.SharedContainers.Database;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.function.ThrowingSupplier;

/**
 * The migrate one-shot (ADR-0027) against seller-api's real migrations: it migrates as {@code seller_api_owner} using
 * the service's own application.yaml, leaves the runtime role DML only, and its context holds Flyway and nothing
 * else even though Kafka, Redis, Modulith and the web stack are on this module's classpath.
 */
class MigrateOneShotTests {

    private static final String SCHEMA = "seller_api";
    private static final String OWNER = SharedContainers.ownerRole(SCHEMA);
    private static final String APP = SharedContainers.appRole(SCHEMA);
    private static final String PASSWORD = SharedContainers.ROLE_PASSWORD;
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Test
    void migratesTheRealMigrationsAsOwnerOnAFreshDatabase() throws SQLException {
        Database db = SharedContainers.newPostgresDatabase();

        assertThat(DbMigrate.run(ownerArgs(db))).isZero();

        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), OWNER, PASSWORD);
                Statement statement = connection.createStatement()) {
            List<String> versions = new ArrayList<>();
            try (ResultSet history = statement.executeQuery(
                    "SELECT version, success FROM seller_api.flyway_schema_history ORDER BY installed_rank")) {
                while (history.next()) {
                    assertThat(history.getBoolean("success")).isTrue();
                    versions.add(history.getString("version"));
                }
            }
            assertThat(versions).containsExactly("1", "2", "3");
            // Tables are owned by the owner role, and the app_role placeholder of the service's yaml was resolved.
            try (ResultSet owner = statement.executeQuery(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'seller_api' AND tablename = 'settlement'")) {
                assertThat(owner.next()).isTrue();
                assertThat(owner.getString(1)).isEqualTo(OWNER);
            }
        }
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), APP, PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM seller_api.settlement")) {
            assertThat(rows.next()).isTrue();
        }
    }

    @Test
    void theAppRoleCanNotAlterTheMigratedSchema() {
        Database db = SharedContainers.newPostgresDatabase();
        assertThat(DbMigrate.run(ownerArgs(db))).isZero();

        assertThatThrownBy(() -> {
                    try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), APP, PASSWORD);
                            Statement statement = connection.createStatement()) {
                        statement.execute("ALTER TABLE seller_api.settlement ADD COLUMN intruder int");
                    }
                })
                .isInstanceOfSatisfying(
                        SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
    }

    @Test
    void theOneShotContextHasNoKafkaRedisModulithOrWebServerBeans() {
        Database db = SharedContainers.newPostgresDatabase();
        // Not vacuous: the libraries really are on this module's classpath.
        assertThat(isPresent("org.springframework.kafka.core.KafkaTemplate")).isTrue();
        assertThat(isPresent("org.springframework.data.redis.connection.RedisConnectionFactory"))
                .isTrue();
        assertThat(isPresent("org.apache.catalina.startup.Tomcat")).isTrue();
        AtomicReference<Snapshot> snapshot = new AtomicReference<>();

        ThrowingSupplier<Integer> run = () -> DbMigrate.run(ownerArgs(db));
        int exit = SpringApplication.withHook(
                application -> new SpringApplicationRunListener() {
                    @Override
                    public void ready(ConfigurableApplicationContext context, Duration timeTaken) {
                        List<String> types = new ArrayList<>();
                        for (String name : context.getBeanDefinitionNames()) {
                            Class<?> type = context.getType(name, false);
                            types.add(type == null ? name : type.getName());
                        }
                        snapshot.set(new Snapshot(context.getClass().getName(), types));
                    }
                },
                run);

        assertThat(exit).isZero();
        Snapshot seen = snapshot.get();
        assertThat(seen).isNotNull();
        assertThat(seen.contextType()).doesNotContain("Web");
        assertThat(seen.beanTypes())
                .anyMatch(type -> type.equals("org.flywaydb.core.Flyway"))
                .noneMatch(type -> type.startsWith("org.springframework.kafka"))
                .noneMatch(type -> type.startsWith("org.springframework.data.redis"))
                .noneMatch(type -> type.startsWith("org.springframework.modulith"))
                .noneMatch(type -> type.startsWith("org.springframework.web"))
                .noneMatch(type -> type.startsWith("org.springframework.boot.web"))
                .noneMatch(type -> type.startsWith("org.springframework.security"))
                .noneMatch(type -> type.startsWith("io.github.orhanyarkin.saiman.sellerapi"))
                .noneMatch(type -> type.startsWith("io.github.orhanyarkin.x402"))
                .noneMatch(type -> type.contains("DataSource"));
    }

    private record Snapshot(String contextType, List<String> beanTypes) {}

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, MigrateOneShotTests.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** What compose supplies: the owner credentials; schemas and placeholders come from application.yaml. */
    static String[] ownerArgs(Database db) {
        return new String[] {
            "--saiman.run-mode=migrate",
            "--spring.datasource.url=" + db.jdbcUrl(),
            "--spring.flyway.user=" + OWNER,
            "--spring.flyway.password=" + PASSWORD
        };
    }
}
