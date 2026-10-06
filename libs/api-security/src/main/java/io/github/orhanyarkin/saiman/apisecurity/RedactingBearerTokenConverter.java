package io.github.orhanyarkin.saiman.apisecurity;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

/**
 * Like Spring's {@code BearerTokenAuthenticationConverter} (header only), but the unauthenticated token it produces
 * does not expose the raw token through {@code getPrincipal()}, {@code getName()} or {@code toString()}. Spring's
 * {@link BearerTokenAuthenticationToken} returns the raw token as its principal, so any log line, event listener or
 * audit hook that prints the authentication <em>request</em> (for example on an authentication failure event) would
 * print the token. The verifier reads it only through {@link BearerTokenAuthenticationToken#getToken()}.
 */
final class RedactingBearerTokenConverter implements AuthenticationConverter {

    static final String REDACTED = "[PROTECTED]";

    private final BearerTokenResolver resolver;
    private final AuthenticationDetailsSource<HttpServletRequest, ?> details = new WebAuthenticationDetailsSource();

    RedactingBearerTokenConverter() {
        DefaultBearerTokenResolver headerOnly = new DefaultBearerTokenResolver();
        headerOnly.setAllowUriQueryParameter(false);
        headerOnly.setAllowFormEncodedBodyParameter(false);
        this.resolver = headerOnly;
    }

    @Override
    public @Nullable Authentication convert(HttpServletRequest request) {
        String token = resolver.resolve(request);
        if (token == null || token.isEmpty()) {
            return null;
        }
        RedactedBearerToken authentication = new RedactedBearerToken(token);
        authentication.setDetails(details.buildDetails(request));
        return authentication;
    }

    /** A bearer token request whose principal, name and string form never contain the token. */
    static final class RedactedBearerToken extends BearerTokenAuthenticationToken {

        private static final long serialVersionUID = 1L;

        RedactedBearerToken(String token) {
            super(token);
        }

        @Override
        public Object getPrincipal() {
            return REDACTED;
        }

        @Override
        public Object getCredentials() {
            return REDACTED;
        }

        @Override
        public String toString() {
            return "RedactedBearerToken[" + REDACTED + "]";
        }
    }
}
