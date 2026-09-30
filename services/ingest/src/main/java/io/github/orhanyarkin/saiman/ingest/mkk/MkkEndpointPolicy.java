package io.github.orhanyarkin.saiman.ingest.mkk;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which base URL the MKK client may send its {@code Authorization: Basic} credential to.
 *
 * <p>The production policy is {@link #strict()}: https and an MKK API gateway host, nothing else, so
 * an environment variable cannot redirect the credential to another host or downgrade it to http.
 * Tests that talk to a local stub server register their own policy <em>bean</em>; there is
 * deliberately no property that loosens the check.
 */
@FunctionalInterface
public interface MkkEndpointPolicy {

    Set<String> ALLOWED_HOSTS = Set.of("apigwdev.mkk.com.tr", "apigw.mkk.com.tr");

    /** @throws IllegalStateException when the URL is not acceptable (the message never echoes the URL) */
    void check(String baseUrl);

    static MkkEndpointPolicy strict() {
        return baseUrl -> {
            URI uri;
            try {
                uri = URI.create(baseUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("saiman.ingest.mkk.base-url is not a valid URI");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalStateException("saiman.ingest.mkk.base-url must use https");
            }
            if (uri.getRawUserInfo() != null) {
                throw new IllegalStateException("saiman.ingest.mkk.base-url must not carry user info");
            }
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!ALLOWED_HOSTS.contains(host)) {
                throw new IllegalStateException(
                        "saiman.ingest.mkk.base-url host is not an allowed MKK gateway (" + ALLOWED_HOSTS + ")");
            }
        };
    }
}
