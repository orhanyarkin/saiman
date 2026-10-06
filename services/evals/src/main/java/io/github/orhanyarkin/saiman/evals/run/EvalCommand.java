package io.github.orhanyarkin.saiman.evals.run;

import io.github.orhanyarkin.saiman.evals.report.EvalReport;
import java.time.Clock;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The CLI entry point. Gated so that loading the context in a test never runs an eval. A run in which any
 * query failed ends with a non-zero exit code (the exception makes Spring Boot exit with 1), after the report
 * has been written.
 */
@Configuration(proxyBeanMethods = false)
class EvalCommand {

    @Bean
    Clock evalClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnBooleanProperty("saiman.evals.run-on-startup")
    CommandLineRunner runEvals(EvalRunner runner) {
        return args -> {
            EvalReport report = runner.run();
            if (report.errors() > 0) {
                throw new IllegalStateException(report.errors() + " eval item(s) failed; see the report");
            }
        };
    }
}
