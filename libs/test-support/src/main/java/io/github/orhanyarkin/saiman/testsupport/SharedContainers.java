package io.github.orhanyarkin.saiman.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Postgres, one Kafka (KRaft) and one Redis per test JVM, started on first use and never stopped by a test or
 * a Spring context: Testcontainers' reaper (Ryuk) removes them when the JVM exits.
 *
 * <p>Postgres is shared as a server, not as a database: {@link #newPostgresDatabase()} gives every Spring context its
 * own database, so contexts stay isolated. Kafka and Redis are shared as they are: Spring pauses inactive cached
 * contexts (their listener containers and task schedulers stop), application listeners keep their fixed consumer group
 * (a resumed context continues from the group's committed offset, so it does not re-read records another context
 * consumed), and tests use unique keys, ids and test-side consumer groups rather than {@code @DirtiesContext}.
 */
public final class SharedContainers {

    /** Same tags as deploy/compose/docker-compose.yml. */
    public static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-trixie").asCompatibleSubstituteFor("postgres");

    public static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.3.1");

    public static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:8.10.2-alpine");

    public static final int REDIS_PORT = 6379;

    /**
     * Every cached context keeps its own connection pool open (up to 10 connections) until the JVM exits, and a
     * module can cache dozens of contexts; the default of 100 connections is not enough.
     */
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withCommand("postgres", "-c", "max_connections=1000");

    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(REDIS_PORT);

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private SharedContainers() {}

    /** Connection settings of one database on the shared Postgres. */
    public record Database(String jdbcUrl, String username, String password) {}

    /** The JVM's Postgres (pgvector), started if needed. */
    public static PostgreSQLContainer postgres() {
        return started(POSTGRES);
    }

    /**
     * Creates an empty database on {@link #postgres()} and returns its connection settings. The container's default
     * user owns it, so migrations may create extensions such as {@code vector}.
     */
    public static Database newPostgresDatabase() {
        PostgreSQLContainer postgres = postgres();
        String name = "test_" + DATABASES.incrementAndGet();
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create test database " + name, e);
        }
        String url = postgres.getJdbcUrl().replaceFirst("/" + postgres.getDatabaseName() + "(?=\\?|$)", "/" + name);
        return new Database(url, postgres.getUsername(), postgres.getPassword());
    }

    /** The JVM's single-node KRaft Kafka broker, started if needed. */
    public static KafkaContainer kafka() {
        return started(KAFKA);
    }

    /** The JVM's Redis, started if needed. */
    public static GenericContainer<?> redis() {
        return started(REDIS);
    }

    /** {@code redis://host:port} of {@link #redis()}, for clients built without Spring Boot. */
    public static String redisUrl() {
        GenericContainer<?> redis = redis();
        return "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT);
    }

    private static synchronized <T extends GenericContainer<?>> T started(T container) {
        if (!container.isRunning()) {
            container.start();
        }
        return container;
    }
}
