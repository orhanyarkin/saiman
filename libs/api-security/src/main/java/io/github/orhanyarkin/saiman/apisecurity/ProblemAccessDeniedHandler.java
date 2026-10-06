package io.github.orhanyarkin.saiman.apisecurity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers an authenticated request whose role does not allow it with 403 and a fixed RFC 9457 problem body. No {@code
 * WWW-Authenticate} header (Spring's {@code BearerTokenAccessDeniedHandler} would add {@code insufficient_scope}).
 */
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    /** The fixed problem body of every 403. */
    public static final String BODY = ProblemResponses.FORBIDDEN_BODY;

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        ProblemResponses.write(response, HttpServletResponse.SC_FORBIDDEN, BODY);
    }
}
