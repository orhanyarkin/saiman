package io.github.orhanyarkin.saiman.sellerapi.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code seller.http.*}.
 *
 * @param maxBodyBytes largest request body accepted on the paid endpoints (a question is at most 500
 *     characters, so 4 KiB is generous)
 * @param maxJsonStringLength longest single JSON string the request parser accepts
 */
@ConfigurationProperties("seller.http")
public record HttpLimitsProperties(
        @DefaultValue("4096") long maxBodyBytes,
        @DefaultValue("65536") int maxJsonStringLength) {}
