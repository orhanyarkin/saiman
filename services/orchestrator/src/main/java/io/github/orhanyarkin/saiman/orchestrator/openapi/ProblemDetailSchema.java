package io.github.orhanyarkin.saiman.orchestrator.openapi;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The RFC 9457 error body as the orchestrator sends it, for the OpenAPI document only (the handlers
 * return Spring's {@code ProblemDetail}, whose reflected schema would show its extension map as a
 * property). The request guard's own errors carry just {@code status} and {@code detail}.
 */
@Schema(name = "ProblemDetail")
public record ProblemDetailSchema(
        @Nullable String type,
        @Nullable String title,
        int status,
        @Nullable String detail,
        @Nullable String instance) {}
