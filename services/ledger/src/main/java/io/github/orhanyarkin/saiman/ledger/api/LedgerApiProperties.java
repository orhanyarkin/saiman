package io.github.orhanyarkin.saiman.ledger.api;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.ledger.api.*}.
 *
 * @param allowedHosts {@code Host} header values (with or without a port) the ledger answers to; anything else
 *     is a DNS-rebinding attempt and gets a 400
 */
@ConfigurationProperties("saiman.ledger.api")
public record LedgerApiProperties(
        @DefaultValue({"localhost", "127.0.0.1", "[::1]", "ledger"})
        List<String> allowedHosts) {

    public LedgerApiProperties {
        allowedHosts = List.copyOf(allowedHosts);
    }
}
