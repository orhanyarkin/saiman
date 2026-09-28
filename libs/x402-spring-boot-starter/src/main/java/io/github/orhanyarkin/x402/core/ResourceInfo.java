package io.github.orhanyarkin.x402.core;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Describes the protected resource a client is paying to access.
 *
 * <p>Mirrors the x402 v2 {@code ResourceInfo} schema (specs/x402-specification-v2.md, section
 * 5.1.2). Only {@link #url()} is required; every other field is a hint for discovery and display.
 *
 * @param url URL of the protected resource
 * @param description human-readable description of the resource
 * @param mimeType MIME type of the expected response
 * @param serviceName human-readable name of the service hosting the resource (printable ASCII,
 *     max 32 characters)
 * @param tags topical tags for discovery filtering (max 5 entries, each printable ASCII, max 32
 *     characters)
 * @param iconUrl absolute {@code https}/{@code http} URL to an icon for the service (max 2048
 *     characters)
 */
public record ResourceInfo(
        String url,
        @Nullable String description,
        @Nullable String mimeType,
        @Nullable String serviceName,
        @Nullable List<String> tags,
        @Nullable String iconUrl) {

    public ResourceInfo {
        Objects.requireNonNull(url, "url must not be null");
    }
}
