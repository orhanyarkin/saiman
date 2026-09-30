package io.github.orhanyarkin.saiman.orchestrator.approval;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.orchestrator.api.*}.
 *
 * @param allowedHosts {@code Host} header values (with or without a port) the {@code /api/**}
 *     endpoints answer to; anything else is a DNS-rebinding attempt and gets a 400
 */
@ConfigurationProperties("saiman.orchestrator.api")
public record ApiGuardProperties(
        @DefaultValue({"localhost", "127.0.0.1", "[::1]", "orchestrator"})
        List<String> allowedHosts) {

    public ApiGuardProperties {
        allowedHosts = List.copyOf(allowedHosts);
    }
}
