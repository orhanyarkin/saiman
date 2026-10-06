package io.github.orhanyarkin.saiman.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.dbmigrate.DbMigrate;
import io.github.orhanyarkin.saiman.testsupport.KafkaContainerConfiguration;
import io.github.orhanyarkin.saiman.testsupport.SharedContainers;
import io.github.orhanyarkin.saiman.testsupport.SharedContainers.Database;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The server as deployed after ADR-0027: it connects as {@code ledger_app} only, with
 * {@code SPRING_FLYWAY_ENABLED=false}, on a database the one-shot migrated beforehand.
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
@Import({KafkaContainerConfiguration.class, FakeChain.class, FakeSellerCreditNotes.class})
class ServerWithoutFlywayTests {

    private static final Database MIGRATED = migrate();

    private static Database migrate() {
        Database db = SharedContainers.newPostgresDatabase();
        assertThat(DbMigrate.run(MigrateOneShotTests.ownerArgs(db))).isZero();
        return db;
    }

    @DynamicPropertySource
    static void appRole(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MIGRATED::jdbcUrl);
        registry.add("spring.datasource.username", () -> SharedContainers.appRole("ledger"));
        registry.add("spring.datasource.password", () -> SharedContainers.ROLE_PASSWORD);
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationContext context;

    @Test
    void startsAsTheAppRoleWithoutMigratingAndReadsTheMigratedSchema() {
        assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("ledger_app");
        assertThat(jdbc.sql("SELECT count(*) FROM account").query(Long.class).single())
                .isGreaterThanOrEqualTo(0);
    }
}
