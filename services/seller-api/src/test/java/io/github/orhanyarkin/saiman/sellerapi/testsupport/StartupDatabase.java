package io.github.orhanyarkin.saiman.sellerapi.testsupport;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Postgres per test JVM for tests that start the application with {@code SpringApplicationBuilder} instead of
 * {@code @SpringBootTest} (startup-failure tests). seller-api needs a database since M4 (schema {@code seller_api});
 * without this these tests silently used whatever Postgres listened on localhost:5432 (the compose stack) and failed
 * in CI. Started once, stopped by Testcontainers' reaper when the JVM exits.
 */
public final class StartupDatabase {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-trixie").asCompatibleSubstituteFor("postgres"));

    private StartupDatabase() {}

    /** Command-line arguments that point the application at the shared container. */
    public static synchronized String[] args() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        return new String[] {
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword()
        };
    }

    /** {@code args} followed by the database arguments. */
    public static String[] with(String... args) {
        String[] db = args();
        String[] all = new String[args.length + db.length];
        System.arraycopy(args, 0, all, 0, args.length);
        System.arraycopy(db, 0, all, args.length, db.length);
        return all;
    }
}
