package io.github.orhanyarkin.saiman.ingest.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.dbmigrate.DbMigrate;
import io.github.orhanyarkin.saiman.ingest.TestModelRouterConfiguration;
import io.github.orhanyarkin.saiman.testsupport.SharedContainers;
import io.github.orhanyarkin.saiman.testsupport.SharedContainers.Database;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Production shape (ADR-0027, ADR-0024): the database was migrated by the one-shot as {@code ingest_owner}; the server
 * then runs as {@code ingest_app} with Flyway off and no owner credential. The MKK credential is blank, as when the
 * secret is not mounted.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=false", "saiman.ingest.mkk.credentials="})
@Import(TestModelRouterConfiguration.class) // no provider key or Redis: the router is a test double
class AppRoleRuntimeTests {

    @DynamicPropertySource
    static void migratedDatabase(DynamicPropertyRegistry registry) {
        Database db = SharedContainers.newPostgresDatabase();
        int exit = DbMigrate.run(new String[] {
            "--spring.datasource.url=" + db.jdbcUrl(),
            "--spring.flyway.user=" + SharedContainers.ownerRole("ingest"),
            "--spring.flyway.password=" + SharedContainers.ROLE_PASSWORD
        });
        assertThat(exit).isZero();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", () -> SharedContainers.appRole("ingest"));
        registry.add("spring.datasource.password", () -> SharedContainers.ROLE_PASSWORD);
    }

    @Autowired
    private JdbcClient jdbc;

    @Test
    void serverStartsAsAppRoleWithoutFlywayAndCanRead() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("ingest_app");
        assertThat(jdbc.sql("SELECT count(*) FROM ingest.source_document")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void appRoleCannotAlterTables() {
        assertThatThrownBy(() -> jdbc.sql("ALTER TABLE ingest.source_document ADD COLUMN sneaky text")
                        .update())
                .rootCause()
                .hasMessageContaining("must be owner of table source_document");
    }
}
