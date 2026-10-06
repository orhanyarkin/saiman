package io.github.orhanyarkin.saiman.ingest.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ingest.TestModelRouterConfiguration;
import io.github.orhanyarkin.saiman.ingest.pipeline.IngestJob;
import io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * With the MKK credential secret absent (blank) and the default configuration, the server starts and ingests nothing:
 * the only automatic trigger is the {@code BackfillRunner}, which exists only when
 * {@code saiman.ingest.backfill.enabled=true} (default {@code false}); there is no {@code @Scheduled} job. A blank
 * credential on its own does not gate anything: it just sends no Authorization header.
 */
@SpringBootTest(properties = "saiman.ingest.mkk.credentials=")
@Import({PostgresContainerConfiguration.class, TestModelRouterConfiguration.class})
class BlankCredentialIdleTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private IngestJob job;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void noAutomaticIngestTriggerExists() {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                .isEmpty();
        assertThat(context.getBeansOfType(org.springframework.boot.ApplicationRunner.class)
                        .values())
                .noneMatch(runner -> runner.getClass().getSimpleName().equals("BackfillRunner"));
    }

    @Test
    void nothingWasIngestedAndNoRunIsInProgress() {
        assertThat(job.isRunning()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM source_document")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM source_cursor")
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
