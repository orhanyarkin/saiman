package io.github.orhanyarkin.saiman.sellerapi.testsupport;

import io.github.orhanyarkin.saiman.testsupport.SharedContainers;
import org.jspecify.annotations.Nullable;

/**
 * A database on the JVM's shared Postgres ({@link SharedContainers}) as command-line arguments, for tests that start the
 * application with {@code SpringApplicationBuilder} instead of {@code @SpringBootTest} (startup-failure tests).
 * seller-api needs a database since M4 (schema {@code seller_api}); without this these tests silently used whatever
 * Postgres listened on localhost:5432 (the compose stack) and failed in CI.
 *
 * <p>The database is created once per JVM with {@link SharedContainers#newPostgresDatabase()}, which also creates the
 * service roles and the schema owned by {@code seller_api_owner}: the application connects as {@code seller_api_app}
 * and Flyway as {@code seller_api_owner}, as in production (ADR-0024). The startup tests share it: they run one at a
 * time and the migrations are idempotent.
 */
public final class StartupDatabase {

    private static final String SCHEMA = "seller_api";

    private static SharedContainers.@Nullable Database database;

    private StartupDatabase() {}

    private static synchronized SharedContainers.Database database() {
        if (database == null) {
            database = SharedContainers.newPostgresDatabase();
        }
        return database;
    }

    /** Command-line arguments that point the application at the shared container with the production roles. */
    public static String[] args() {
        SharedContainers.Database db = database();
        return new String[] {
            "--spring.datasource.url=" + db.jdbcUrl(),
            "--spring.datasource.username=" + SharedContainers.appRole(SCHEMA),
            "--spring.datasource.password=" + SharedContainers.ROLE_PASSWORD,
            "--spring.flyway.user=" + SharedContainers.ownerRole(SCHEMA),
            "--spring.flyway.password=" + SharedContainers.ROLE_PASSWORD
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
