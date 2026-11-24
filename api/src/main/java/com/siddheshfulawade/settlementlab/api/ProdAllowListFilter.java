package com.siddheshfulawade.settlementlab.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The public surface in the {@code prod} profile (REQ-API-010): {@code /api/v1/runs}, what is below it,
 * and {@code /actuator/health}. Everything else is a 404, whatever the method.
 *
 * <p>Invariant: it lists what is allowed, not what is hidden, so a controller added later is not public
 * until someone adds it here. The local controllers are also not registered in {@code prod}; this is the
 * second lock. The comparison uses the path exactly as sent, so an unusual spelling is refused.
 */
final class ProdAllowListFilter extends OncePerRequestFilter implements Ordered {

    private static final String RUNS = "/api/v1/runs";
    private static final String HEALTH = "/actuator/health";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 15; // after CORS, before any body is read
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals(RUNS) || path.startsWith(RUNS + "/") || path.equals(HEALTH)) {
            chain.doFilter(request, response);
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
