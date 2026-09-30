package io.github.orhanyarkin.saiman.ingest.guard;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.ingest.internal.*}.
 *
 * @param allowedHosts accepted {@code Host} header values for {@code /internal/**}; an entry without
 *     a port matches that host on any port, an entry with a port matches exactly
 */
@ConfigurationProperties("saiman.ingest.internal")
public record InternalGuardProperties(
        @DefaultValue({"localhost", "127.0.0.1", "[::1]", "ingest", "ingest:8083"})
        List<String> allowedHosts) {}
