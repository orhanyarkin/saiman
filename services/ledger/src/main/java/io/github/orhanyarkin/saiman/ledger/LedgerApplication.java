package io.github.orhanyarkin.saiman.ledger;

import io.github.orhanyarkin.saiman.dbmigrate.DbMigrate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LedgerApplication {

    public static void main(String[] args) {
        if (DbMigrate.requested(args)) {
            // One-shot (ADR-0027): migrate as ledger_owner and exit, without starting the server.
            System.exit(DbMigrate.run(args));
        }
        SpringApplication.run(LedgerApplication.class, args);
    }
}
