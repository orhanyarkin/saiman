package io.github.orhanyarkin.saiman.sellerapi.settlement;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code seller.internal.*}: the compose-internal surface the ledger reads ({@code /internal/**}).
 *
 * @param allowedHosts exact {@code Host} header values (port included when the caller sends one) that may reach
 *     {@code /internal/**}. The default is the compose service name only: the published port on the host
 *     ({@code localhost:8081}) and any rebound browser name are refused.
 */
@ConfigurationProperties("seller.internal")
record InternalApiProperties(
        @DefaultValue({"seller-api", "seller-api:8081"}) List<String> allowedHosts) {

    InternalApiProperties {
        allowedHosts = allowedHosts.stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty())
                .toList();
        if (allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("seller.internal.allowed-hosts must not be empty");
        }
    }
}
