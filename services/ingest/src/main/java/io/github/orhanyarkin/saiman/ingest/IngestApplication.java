package io.github.orhanyarkin.saiman.ingest;

import io.github.orhanyarkin.saiman.dbmigrate.DbMigrate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the ingest service: MKK KAP disclosure fetch, structure-aware chunking,
 * embedding and pgvector indexing, plus the internal hybrid-retrieval API (ADR-0010, ADR-0012).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IngestApplication {

    public static void main(String[] args) {
        if (DbMigrate.requested(args)) { // one image, two entry points (ADR-0027)
            System.exit(DbMigrate.run(args));
        }
        SpringApplication.run(IngestApplication.class, args);
    }
}
