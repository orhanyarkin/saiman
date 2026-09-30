package io.github.orhanyarkin.saiman.ingest.pipeline;

import java.sql.DriverManager;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PipelineConfiguration {

    /** A plain (non-pooled) connection to the same database, from Boot's connection details. */
    @Bean
    LockConnectionFactory lockConnectionFactory(JdbcConnectionDetails details) {
        return () -> DriverManager.getConnection(details.getJdbcUrl(), details.getUsername(), details.getPassword());
    }
}
