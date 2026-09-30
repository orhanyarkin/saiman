package io.github.orhanyarkin.saiman.orchestrator.approval;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects {@code /api/**}, which has no authentication yet (orchestrator auth is M6 hardening),
 * from a web page in the operator's browser. Same pattern as ingest's {@code
 * InternalRequestGuardFilter}, stricter on writes:
 *
 * <ul>
 *   <li><b>DNS rebinding:</b> the {@code Host} header must be in {@code
 *       saiman.orchestrator.api.allowed-hosts}, else 400.
 *   <li><b>CSRF:</b> a state-changing request (POST/PUT/PATCH/DELETE) must carry <em>both</em>
 *       {@code Content-Type: application/json} and {@code X-Saiman-Csrf: 1}, else 403. A cross-site
 *       page can't send either without a CORS preflight, which is never answered (no CORS config).
 * </ul>
 */
@Component
@EnableConfigurationProperties(ApiGuardProperties.class)
public class ApiRequestGuardFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-Saiman-Csrf";
    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final List<String> allowedHosts;

    public ApiRequestGuardFilter(ApiGuardProperties properties) {
        this.allowedHosts = properties.allowedHosts().stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/api") || path.startsWith("/api/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hostAllowed(request.getHeader(HttpHeaders.HOST))) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "Host not allowed");
            return;
        }
        if (STATE_CHANGING.contains(request.getMethod())
                && !("1".equals(request.getHeader(CSRF_HEADER)) && isJson(request.getContentType()))) {
            reject(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "State-changing requests need Content-Type application/json and " + CSRF_HEADER + ": 1");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean hostAllowed(@Nullable String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.toLowerCase(Locale.ROOT).trim();
        if (allowedHosts.contains(host)) {
            return true;
        }
        // Strip the port: "[::1]:80" -> "[::1]", "localhost:80" -> "localhost".
        int portColon = host.startsWith("[") ? host.indexOf("]:") + 1 : host.lastIndexOf(':');
        return portColon > 0 && allowedHosts.contains(host.substring(0, portColon));
    }

    private static boolean isJson(@Nullable String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void reject(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
