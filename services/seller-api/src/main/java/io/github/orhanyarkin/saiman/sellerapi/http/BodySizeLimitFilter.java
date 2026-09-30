package io.github.orhanyarkin.saiman.sellerapi.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses request bodies that are too large or of unknown length with {@code 413} before anything
 * else looks at them: before the x402 filter and interceptor (so no nonce is claimed and the
 * facilitator is not called for a request that will not be served), before body parsing and before
 * ingest or the model are involved.
 *
 * <p>Only the declared {@code Content-Length} is trusted for the decision: a body without one
 * (chunked) is refused outright rather than counted while streaming, because the endpoints this
 * protects take small JSON documents and every legitimate client sends a length. The response is a
 * fixed Problem Details body that echoes nothing of the request.
 */
public final class BodySizeLimitFilter extends OncePerRequestFilter {

    private static final Set<String> METHODS_WITH_BODY = Set.of("POST", "PUT", "PATCH");

    private final long maxBodyBytes;

    public BodySizeLimitFilter(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !METHODS_WITH_BODY.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length < 0 || length > maxBodyBytes) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter()
                    .write("{\"type\":\"about:blank\",\"title\":\"Content Too Large\",\"status\":413,"
                            + "\"detail\":\"The request body is too large or its length is not declared\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
