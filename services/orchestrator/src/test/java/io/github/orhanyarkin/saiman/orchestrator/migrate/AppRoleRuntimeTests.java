package io.github.orhanyarkin.saiman.orchestrator.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.dbmigrate.DbMigrate;
import io.github.orhanyarkin.saiman.testsupport.SharedContainers;
import io.github.orhanyarkin.saiman.testsupport.SharedContainers.Database;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Production shape (ADR-0027, ADR-0024): the database was migrated by the one-shot as {@code orchestrator_owner}; the
 * server then runs as {@code orchestrator_app} with Flyway off and no owner credential.
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
class AppRoleRuntimeTests {

    @DynamicPropertySource
    static void migratedDatabase(DynamicPropertyRegistry registry) {
        Database db = SharedContainers.newPostgresDatabase();
        int exit = DbMigrate.run(new String[] {
            "--spring.datasource.url=" + db.jdbcUrl(),
            "--spring.flyway.user=" + SharedContainers.ownerRole("orchestrator"),
            "--spring.flyway.password=" + SharedContainers.ROLE_PASSWORD
        });
        assertThat(exit).isZero();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", () -> SharedContainers.appRole("orchestrator"));
        registry.add("spring.datasource.password", () -> SharedContainers.ROLE_PASSWORD);
    }

    @Autowired
    private JdbcClient jdbc;

    @Test
    void serverStartsAsAppRoleWithoutFlywayAndCanRead() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("orchestrator_app");
        assertThat(jdbc.sql("SELECT count(*) FROM orchestrator.run")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void appRoleCannotAlterTables() {
        assertThatThrownBy(() -> jdbc.sql("ALTER TABLE orchestrator.run ADD COLUMN sneaky text")
                        .update())
                .rootCause()
                .hasMessageContaining("must be owner of table run");
    }
}
