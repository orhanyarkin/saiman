package io.github.orhanyarkin.saiman.ingest.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.ingest.IngestIntegrationTests;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** V3__grants.sql (ADR-0024): the pool runs as {@code ingest_app}, which can do DML and nothing else. */
class RuntimeRoleGrantsTests extends IngestIntegrationTests {

    @Test
    void theRuntimePoolIsTheAppRole() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("ingest_app");
    }

    @Test
    void dmlWorksIncludingSequences() {
        jdbc.sql("""
                        INSERT INTO dead_letter (source, external_id, stage, error_class, error_message, attempts)
                        VALUES ('kap', 'grants-test', 'EXTRACT', 'X', 'x', 1)
                        """).update();
        assertThat(count("dead_letter")).isEqualTo(1);
        jdbc.sql("DELETE FROM dead_letter").update();
    }

    @Test
    void ddlTruncateAndFlywayHistoryAreOutOfReach() {
        assertThatThrownBy(() -> jdbc.sql("CREATE TABLE ingest.sneaky (id int)").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE chunk").update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DROP TABLE source_cursor").update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("SELECT count(*) FROM ingest.flyway_schema_history")
                        .query(Long.class)
                        .single())
                .isInstanceOf(DataAccessException.class);
    }
}
