package io.github.orhanyarkin.saiman.evals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;

/**
 * Entry point for the evals CLI app: golden-set evaluation and cost/quality reports over the
 * model router's routes (arrives in M6). M0 wires up the Spring context only; there is nothing to
 * evaluate yet.
 */
@SpringBootApplication
public class EvalsApplication {

    private static final Logger log = LoggerFactory.getLogger(EvalsApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(EvalsApplication.class, args);
    }

    // Gated so that loading the context in a test never runs an eval (which will cost money from M6).
    @Bean
    @ConditionalOnBooleanProperty(name = "saiman.evals.run-on-startup", matchIfMissing = true)
    CommandLineRunner logArrival() {
        return args -> log.info("Eval harness arrives in M6 — nothing to run yet.");
    }
}
