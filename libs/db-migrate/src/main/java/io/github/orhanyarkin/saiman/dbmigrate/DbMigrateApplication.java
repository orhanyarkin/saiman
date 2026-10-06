package io.github.orhanyarkin.saiman.dbmigrate;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * The whole application context of the migrate one-shot: Flyway and nothing else. Package-private and outside every
 * service's component-scan root, so a service never picks it up by accident.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class DbMigrateApplication {

    @Bean
    MigrationOutcome migrationOutcome() {
        return new MigrationOutcome();
    }

    @Bean
    FlywayMigrationStrategy dbMigrateStrategy(Environment environment, MigrationOutcome outcome) {
        return DbMigrate.loggingStrategy(environment, outcome);
    }
}
