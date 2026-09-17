package dev.abgleich.bootstrap.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request one id that appears in each log line it causes, and sends it back in the response so a
 * person reporting a problem can quote it.
 *
 * <p>A caller may supply its own id to tie our logs to theirs. That header is untrusted input like any other
 * (B32, B41): it is accepted only if it is short and alphanumeric, so nothing can smuggle newlines, quotes or
 * personal data into the logs.
 */
public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    /** ECS field name, so the JSON logs are searchable as {@code correlation.id}. */
    public static final String MDC_KEY = "correlation.id";

    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = correlationIdOf(request);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String correlationIdOf(HttpServletRequest request) {
        String supplied = request.getHeader(HEADER);
        if (supplied != null && ACCEPTABLE.matcher(supplied).matches()) {
            return supplied;
        }
        return newId();
    }

    /** Short enough to be read out over the phone, random enough not to collide. */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
