package io.github.orhanyarkin.x402.client;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * A small helper so "don't follow redirects" (required for any {@code RestClient} {@link
 * X402PaymentInterceptor} is attached to; see its Javadoc's "Redirects" section) is not only a
 * documented obligation an application has to remember on its own.
 *
 * <p>Most applications should prefer the declarative property {@code
 * spring.http.clients.redirects=dont-follow}, which this starter cannot set on an application's
 * behalf (it would be a surprising, global side effect for a starter to change). This helper is
 * for building a {@code RestClient} programmatically instead — a test, a CLI sample, or any code
 * that constructs its own {@code ClientHttpRequestFactory} rather than relying on Boot's
 * autoconfigured one.
 */
public final class X402RestClients {

    private X402RestClients() {}

    /**
     * Builds a JDK-based {@link ClientHttpRequestFactory} that never follows redirects.
     *
     * @return a {@link ClientHttpRequestFactoryBuilder#jdk()} factory configured with {@link
     *     HttpRedirects#DONT_FOLLOW} and Boot's default connect/read timeouts ({@link
     *     HttpClientSettings#defaults()})
     */
    public static ClientHttpRequestFactory nonRedirectingRequestFactory() {
        return ClientHttpRequestFactoryBuilder.jdk()
                .build(HttpClientSettings.defaults().withRedirects(HttpRedirects.DONT_FOLLOW));
    }
}
