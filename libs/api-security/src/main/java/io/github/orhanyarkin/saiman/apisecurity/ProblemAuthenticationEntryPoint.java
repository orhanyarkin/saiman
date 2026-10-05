package io.github.orhanyarkin.saiman.apisecurity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers a missing, malformed or unknown bearer token with 401, {@code WWW-Authenticate: Bearer} and a fixed RFC 9457
 * problem body. Unlike Spring's {@code BearerTokenAuthenticationEntryPoint} it never copies the exception message into
 * the header or the body, so the answer is the same for every failure and reveals nothing about the token.
 */
public final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** The {@code WWW-Authenticate} value of every 401. */
    public static final String CHALLENGE = "Bearer";

    /** The fixed problem body of every 401. */
    public static final String BODY = ProblemResponses.UNAUTHORIZED_BODY;

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        if (!response.isCommitted()) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        }
        ProblemResponses.write(response, HttpServletResponse.SC_UNAUTHORIZED, BODY);
    }
}
