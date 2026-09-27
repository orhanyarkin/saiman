package io.github.orhanyarkin.saiman.orchestrator.system;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Response for the M0 connectivity check: proves the service is up, can reach Postgres and is
 * traced end to end.
 *
 * @param service the {@code spring.application.name} of the responding service
 * @param dbTime the database server time, read through {@code JdbcClient} (produces a JDBC span)
 * @param traceId the current trace id, or {@code null} if the request was not sampled
 */
public record PingResponse(
        String service, Instant dbTime, @Nullable String traceId) {}
