package com.siddheshfulawade.settlementlab.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The public surface in the {@code prod} profile (REQ-API-010): {@code /api/v1/runs}, what is below it,
 * and {@code /actuator/health}. Everything else is a 404, whatever the method.
 *
 * <p>Invariant: it lists what is allowed, not what is hidden, so a controller added later is not public
 * until someone adds it here. The local controllers are also not registered in {@code prod}; this is the
 * second lock. The comparison uses the path exactly as sent, so an unusual spelling is refused. A refusal is
 * a fixed problem detail: nothing of the request is echoed or logged.
 */
final class ProdAllowListFilter extends OncePerRequestFilter implements Ordered {

    /** The three run paths. Any id without a ";" is let through: an unknown one is "run-not-found". */
    private static final Pattern RUNS = Pattern.compile("/api/v1/runs(/[^/;]+(/events)?)?");

    private static final String HEALTH = "/actuator/health";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 15; // after CORS, before any body is read
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals(HEALTH) || RUNS.matcher(path).matches()) {
            chain.doFilter(request, response);
        } else {
            notFound(response);
        }
    }

    /** Written here, not by sendError, whose error page depends on Accept and shows the framework. */
    private static void notFound(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter()
                .write("{\"type\":\"" + Problems.TYPE_BASE + "not-found\","
                        + "\"title\":\"Not found\",\"status\":404,"
                        + "\"detail\":\"There is nothing at this path.\"}");
    }
}
