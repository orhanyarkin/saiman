package io.github.orhanyarkin.saiman.orchestrator.system;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Response for the M0 connectivity check: proves the service is up, can reach Postgres and is
 * traced end to end.
 *
 * @param service the {@code spring.application.name} of the responding service
 * @param dbTime the database server time, read through {@code JdbcClient} (produces a JDBC span)
 * @param traceId the current trace id, or {@code null} when no span is in scope (tracing disabled);
 *     an unsampled request still has a trace id
 * @param environment where this deployment says it runs ({@code local} or {@code aws-demo}), from
 *     {@code saiman.environment}; the empty string means "unknown" and is never {@code null}. The
 *     web app's own recorded mode uses {@code replay}, which never comes from this property.
 */
public record PingResponse(
        String service, Instant dbTime, @Nullable String traceId, String environment) {}
