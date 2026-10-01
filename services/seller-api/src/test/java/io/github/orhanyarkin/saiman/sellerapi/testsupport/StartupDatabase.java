package io.github.orhanyarkin.saiman.sellerapi.testsupport;

import io.github.orhanyarkin.saiman.testsupport.SharedContainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The JVM's shared Postgres ({@link SharedContainers#postgres()}) as command-line arguments, for tests that start the
 * application with {@code SpringApplicationBuilder} instead of {@code @SpringBootTest} (startup-failure tests).
 * seller-api needs a database since M4 (schema {@code seller_api}); without this these tests silently used whatever
 * Postgres listened on localhost:5432 (the compose stack) and failed in CI. They share the container's default database
 * (not one per context): they run one at a time and the migrations are idempotent.
 */
public final class StartupDatabase {

    private StartupDatabase() {}

    /** Command-line arguments that point the application at the shared container. */
    public static String[] args() {
        PostgreSQLContainer postgres = SharedContainers.postgres();
        return new String[] {
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword()
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
