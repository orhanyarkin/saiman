package io.github.orhanyarkin.saiman.apisecurity;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

/** Writes the fixed RFC 9457 bodies of the 401 and 403 answers. Nothing from the request is echoed. */
final class ProblemResponses {

    static final String UNAUTHORIZED_BODY = "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
            + "\"detail\":\"A valid bearer token is required.\"}";

    static final String FORBIDDEN_BODY = "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
            + "\"detail\":\"The token's role does not allow this request.\"}";

    private ProblemResponses() {}

    static void write(HttpServletResponse response, int status, String body) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }
}
