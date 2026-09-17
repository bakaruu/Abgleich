package dev.abgleich.bootstrap.web;

import dev.abgleich.bootstrap.web.RequestRateLimiter.Decision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * B44: every request that changes data (uploads, example loads, invoices, decisions) counts against the
 * client's budget; reading pages is free. Behind the reverse proxy the client address is the forwarded one,
 * which the servlet container resolves only for trusted internal proxies.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    static final String MESSAGE = "Too many changes from your address. Please wait a minute and try again.";
    private static final Set<String> CHANGING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final RequestRateLimiter limiter;

    public RateLimitFilter(RequestRateLimiter limiter) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CHANGING_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Decision decision = limiter.tryAcquire(request.getRemoteAddr());
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"type\":\"urn:abgleich:problem:rate-limited\",\"title\":\"Too many requests\","
                    + "\"status\":429,\"detail\":\"" + MESSAGE + "\"}");
        } else {
            // A constant text: htmx swaps it in where the result would have appeared.
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.getWriter().write("<div class=\"alert alert-error\" role=\"alert\"><strong>Slow down.</strong> <span>"
                    + MESSAGE + "</span></div>");
        }
    }
}
