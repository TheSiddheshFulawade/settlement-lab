package com.siddheshfulawade.settlementlab.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cross-origin access for the portfolio page (REQ-RUN-008), as a servlet filter rather than Spring MVC's
 * CORS support, so it covers every answer the same way: the runs endpoints, the event stream,
 * {@code /actuator/health} (which has its own handler mapping) and error bodies.
 *
 * <p>Invariant: a response gets CORS headers only if the request's {@code Origin} is, character for
 * character, one of the allowed origins. No wildcard, no pattern, no "reflect whatever came in", and
 * never {@code Access-Control-Allow-Credentials}. A foreign origin gets no CORS header at all, and its
 * preflight is refused with 403, so a browser blocks the page from reading anything. Requests without an
 * {@code Origin} header (curl, servers) are untouched.
 *
 * <p>The headers are set before the rest of the chain runs, so a problem detail from any later step
 * carries them too.
 */
final class RunCorsFilter extends OncePerRequestFilter implements Ordered {

    static final String PRODUCTION_ORIGIN = "https://siddheshfulawade.com";

    private static final Set<String> METHODS = Set.of("GET", "POST");

    private final Set<String> allowedOrigins;

    /**
     * @param previewOrigin the extra origin from {@code settlement.runs.preview-origin}; blank means none
     */
    RunCorsFilter(String previewOrigin) {
        this.allowedOrigins = previewOrigin == null || previewOrigin.isBlank()
                ? Set.of(PRODUCTION_ORIGIN)
                : Set.of(PRODUCTION_ORIGIN, previewOrigin.trim());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.addHeader("Vary", "Origin"); // the answer depends on the Origin: caches must know
        String origin = request.getHeader("Origin");
        boolean allowed = origin != null && allowedOrigins.contains(origin);
        boolean preflight = origin != null
                && "OPTIONS".equals(request.getMethod())
                && request.getHeader("Access-Control-Request-Method") != null;

        if (preflight) {
            answerPreflight(request, response, origin, allowed);
            return;
        }
        if (allowed) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Expose-Headers", "Location, Retry-After");
        }
        chain.doFilter(request, response);
    }

    private static void answerPreflight(
            HttpServletRequest request, HttpServletResponse response, String origin, boolean allowed) {
        if (!allowed
                || !METHODS.contains(request.getHeader("Access-Control-Request-Method"))
                || !onlyContentType(request.getHeader("Access-Control-Request-Headers"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN); // no CORS header: the browser refuses
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Methods", "GET, POST");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setHeader("Access-Control-Max-Age", "600");
        response.setContentLength(0);
    }

    /** True if the preflight asks for no header, or only Content-Type. */
    private static boolean onlyContentType(String requested) {
        if (requested == null || requested.isBlank()) {
            return true;
        }
        for (String header : requested.split(",")) {
            if (!"content-type".equals(header.trim().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
