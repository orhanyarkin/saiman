package io.github.orhanyarkin.saiman.orchestrator.system;

import io.micrometer.tracing.Tracer;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The M0 business-free endpoint: proves the orchestrator is reachable, can read Postgres and
 * propagates a distributed trace from the caller through this service into the database.
 */
@RestController
class PingController {

    private final JdbcClient jdbcClient;
    private final Tracer tracer;
    private final String serviceName;
    private final String environment;

    /**
     * @param environment {@code saiman.environment} (env var {@code SAIMAN_ENVIRONMENT}). The default is the
     *     empty string on purpose and must not become {@code local}: empty means "unknown", so the UI claims no
     *     environment. A {@code local} default would make a deployment that forgot the variable report itself
     *     as local, which is the false banner this field exists to remove. The value is not validated: an
     *     unknown name must degrade to "unknown" in the UI, not fail a health endpoint.
     */
    PingController(
            JdbcClient jdbcClient,
            Tracer tracer,
            @Value("${spring.application.name}") String serviceName,
            @Value("${saiman.environment:}") String environment) {
        this.jdbcClient = jdbcClient;
        this.tracer = tracer;
        this.serviceName = serviceName;
        this.environment = environment;
    }

    @GetMapping("/api/v1/ping")
    PingResponse ping() {
        // select now() produces the JDBC child span (datasource-micrometer).
        OffsetDateTime dbTime =
                jdbcClient.sql("select now()").query(OffsetDateTime.class).single();
        return new PingResponse(serviceName, dbTime.toInstant(), currentTraceId(), environment);
    }

    private @Nullable String currentTraceId() {
        var span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
