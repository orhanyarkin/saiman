package io.github.orhanyarkin.saiman.ingest.guard;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects the service, whose {@code /internal/**} has no authentication (ADR-0012: loopback and
 * the compose network only), from a web page in the developer's browser. The checks apply to every
 * request whatever its path form; deciding by an {@code /internal/} prefix of the raw URI was
 * bypassable ({@code /internal;x=1/}, {@code /%69nternal/}, {@code //internal/}).
 *
 * <ul>
 *   <li><b>DNS rebinding:</b> the {@code Host} header must be in the allowlist, else 400 (health probes excepted). A rebound
 *       attacker domain keeps its own name in {@code Host}.
 *   <li><b>CSRF:</b> a state-changing request (POST/PUT/PATCH/DELETE) on any path must carry
 *       {@code Content-Type: application/json} or {@code X-Saiman-Internal: 1}, else 403. A page can
 *       send a "simple" cross-site POST (form, text/plain) without a preflight, but not with either
 *       of these; the preflight it would then trigger is never answered (no CORS config).
 *   <li><b>Strict paths:</b> a raw URI with {@code ;}, {@code %}, {@code //}, a backslash or dot
 *       segments is refused with 400.
 * </ul>
 */
@Component
public class InternalRequestGuardFilter extends OncePerRequestFilter {

    public static final String INTERNAL_HEADER = "X-Saiman-Internal";
    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final List<String> allowedHosts;

    public InternalRequestGuardFilter(InternalGuardProperties properties) {
        this.allowedHosts = properties.allowedHosts().stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!strictPath(request.getRequestURI())) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "Malformed request path");
            return;
        }
        if (!healthProbe(request) && !hostAllowed(request.getHeader(HttpHeaders.HOST))) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "Host not allowed");
            return;
        }
        if (STATE_CHANGING.contains(request.getMethod()) && !jsonOrMarked(request)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "Missing " + INTERNAL_HEADER + " header");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The raw request line must already be in normal form: no path parameters ({@code ;}), no
     * percent-encoding, no empty segments ({@code //}), no dot segments or backslashes. Tomcat and
     * Spring MVC route on the decoded, normalised path, so any other form could reach a handler the
     * guard did not recognise (the same idea as Spring Security's StrictHttpFirewall).
     */
    private static boolean strictPath(String rawUri) {
        if (rawUri == null || !rawUri.startsWith("/")) {
            return false;
        }
        if (rawUri.indexOf(';') >= 0
                || rawUri.indexOf('%') >= 0
                || rawUri.indexOf('\\') >= 0
                || rawUri.contains("//")) {
            return false;
        }
        return StringUtils.cleanPath(rawUri).equals(rawUri);
    }

    /** Only the exact health endpoint and its probe groups skip the Host allowlist. */
    private static boolean healthProbe(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    private boolean hostAllowed(String hostHeader) {
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

    private static boolean jsonOrMarked(HttpServletRequest request) {
        if ("1".equals(request.getHeader(INTERNAL_HEADER))) {
            return true;
        }
        String contentType = request.getContentType();
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
