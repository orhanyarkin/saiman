package io.github.orhanyarkin.saiman.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ingest service: KAP disclosure and news fetch, structure-aware chunking,
 * embedding and pgvector indexing (arrives in M2).
 */
@SpringBootApplication
public class IngestApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestApplication.class, args);
    }
}
