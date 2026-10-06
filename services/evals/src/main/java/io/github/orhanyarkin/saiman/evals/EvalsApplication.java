package io.github.orhanyarkin.saiman.evals;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the evals CLI app (ADR-0025): golden-set evaluation of retrieval (Tier R) and, from T6b,
 * answers (Tier A), with a Markdown and JSON report. A non-web application: it runs once and exits.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EvalsApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvalsApplication.class, args);
    }
}
