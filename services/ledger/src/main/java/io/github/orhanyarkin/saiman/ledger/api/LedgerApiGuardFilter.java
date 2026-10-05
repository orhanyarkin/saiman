package io.github.orhanyarkin.saiman.ledger.api;

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
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects the ledger from a web page in the operator's browser, in front of the bearer-token authentication
 * ({@link ApiSecurityConfiguration}, ADR-0023) and independent of it. Same posture as the orchestrator's guard. Applied to <em>every</em> request, not
 * only to paths that look like {@code /api/}: Spring MVC matches handlers on the decoded path with
 * {@code ;} parameters removed, so a prefix check on the raw URI ({@code /api;x=1/...}, {@code
 * /%61pi/...}) would let a request reach a handler unguarded.
 *
 * <ol>
 *   <li><b>Path form:</b> a raw request URI containing {@code ;}, {@code %}, {@code \} or {@code
 *       //}, or one that differs from the container's normalised path (dot segments), gets a 400.
 *       After this check the raw path and the path the handler mapping sees are the same string.
 *   <li><b>DNS rebinding:</b> the {@code Host} header must be in {@code
 *       saiman.ledger.api.allowed-hosts}, else 400. The only exemption is a read of {@code
 *       /actuator/health} and its probe sub-paths, which container health checks call.
 *   <li><b>CSRF:</b> a state-changing request (POST/PUT/PATCH/DELETE), on any path, must carry
 *       <em>both</em> {@code Content-Type: application/json} and {@code X-Saiman-Csrf: 1}, else
 *       403. A cross-site page can't send either without a CORS preflight, which is never answered
 *       (no CORS config).
 *   <li><b>Body size:</b> a POST/PUT/PATCH body larger than {@value #MAX_BODY_BYTES} bytes, or one
 *       of unknown length ({@code Transfer-Encoding}), gets a 413 before anything reads it (the
 *       ledger's only body, an empty JSON object to start a reconciliation run, is far smaller).
 * </ol>
 *
 * Error bodies are fixed text: nothing from the request is echoed.
 */
@Component
@Order(LedgerApiGuardFilter.ORDER)
@EnableConfigurationProperties(LedgerApiProperties.class)
public class LedgerApiGuardFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-Saiman-Csrf";

    /**
     * Before Spring Security's filter chain ({@link SecurityFilterProperties#DEFAULT_FILTER_ORDER}): a foreign Host, a
     * non-canonical path or a missing CSRF header is refused before any bearer token is read (ADR-0023).
     */
    public static final int ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10;

    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String HEALTH = "/actuator/health";
    private static final Set<String> WITH_BODY = Set.of("POST", "PUT", "PATCH");

    /** Largest request body accepted, in bytes. */
    public static final int MAX_BODY_BYTES = 16 * 1024;

    private final List<String> allowedHosts;

    public LedgerApiGuardFilter(LedgerApiProperties properties) {
        this.allowedHosts = properties.allowedHosts().stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = canonicalPath(request);
        if (path == null) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "Request path is not in canonical form");
            return;
        }
        boolean stateChanging = STATE_CHANGING.contains(request.getMethod());
        boolean healthProbe = !stateChanging && (path.equals(HEALTH) || path.startsWith(HEALTH + "/"));
        if (!healthProbe && !hostAllowed(request.getHeader(HttpHeaders.HOST))) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "Host not allowed");
            return;
        }
        if (WITH_BODY.contains(request.getMethod()) && !bodySizeAllowed(request)) {
            response.setHeader(HttpHeaders.CONNECTION, "close");
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Request body too large");
            return;
        }
        if (stateChanging && !("1".equals(request.getHeader(CSRF_HEADER)) && isJson(request.getContentType()))) {
            reject(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "State-changing requests need Content-Type application/json and " + CSRF_HEADER + ": 1");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The request path within the application if the raw URI is already canonical, else null.
     * Canonical means no path parameters, no percent-encoding, no backslash, no empty segment, and
     * equal to the container's decoded and normalised {@code servletPath + pathInfo} (so no dot
     * segments either).
     */
    private static @Nullable String canonicalPath(HttpServletRequest request) {
        String raw = request.getRequestURI();
        if (raw == null
                || !raw.startsWith("/")
                || raw.indexOf(';') >= 0
                || raw.indexOf('%') >= 0
                || raw.indexOf('\\') >= 0
                || raw.contains("//")) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (!raw.startsWith(contextPath)) {
            return null;
        }
        String rawPath = raw.substring(contextPath.length());
        String pathInfo = request.getPathInfo();
        String normalised = request.getServletPath() + (pathInfo == null ? "" : pathInfo);
        return rawPath.equals(normalised) ? rawPath : null;
    }

    /**
     * A declared length up to the limit, or no body at all (neither {@code Content-Length} nor
     * {@code Transfer-Encoding}). A chunked body has no length to check up front, so it is refused.
     */
    private static boolean bodySizeAllowed(HttpServletRequest request) {
        if (request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            return false;
        }
        long length = request.getContentLengthLong();
        return length <= MAX_BODY_BYTES; // -1: no Content-Length and no Transfer-Encoding, so no body
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
