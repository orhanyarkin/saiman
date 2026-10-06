package io.github.orhanyarkin.saiman.sellerapi;

import io.github.orhanyarkin.saiman.dbmigrate.DbMigrate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SellerApiApplication {

    public static void main(String[] args) {
        if (DbMigrate.requested(args)) {
            // One-shot (ADR-0027): migrate as seller_api_owner and exit, without starting the server.
            System.exit(DbMigrate.run(args));
        }
        SpringApplication.run(SellerApiApplication.class, args);
    }
}
