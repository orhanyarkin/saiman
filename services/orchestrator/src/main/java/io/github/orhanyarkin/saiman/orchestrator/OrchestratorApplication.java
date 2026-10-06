package io.github.orhanyarkin.saiman.orchestrator;

import io.github.orhanyarkin.saiman.dbmigrate.DbMigrate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrchestratorApplication {

    public static void main(String[] args) {
        if (DbMigrate.requested(args)) { // one image, two entry points (ADR-0027)
            System.exit(DbMigrate.run(args));
        }
        if (DbMigrate.requested(args)) { // one image, two entry points (ADR-0027)
            System.exit(DbMigrate.run(args));
        }
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
