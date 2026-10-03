package com.redditclone.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

// First filter in the chain (see SecurityConfig) — every request, authenticated or not, gets a
// correlation id in MDC before anything else runs, so even an auth failure's own log line and error
// response body (see GlobalExceptionHandler) carry one. Tomcat reuses request-handling threads across
// unrelated requests, and the async workers later re-apply this same MDC key on their own threads
// (OutboxWorker/NotificationOutboxWorker/ImageProcessingWorker/VideoProcessingWorker), so the finally-
// block clear here is load-bearing, not defensive boilerplate — skipping it would leak one user's id into
// a later, unrelated request's log lines.
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "correlationId";
    public static final String HEADER = "X-Correlation-ID";
    private static final int MAX_LENGTH = 128;
    // Deliberately narrow: this value is both logged verbatim and echoed back unescaped on a response
    // header, so a client-supplied value outside this charset (newlines especially) is rejected rather
    // than trusted, closing a log/header-injection path a free-form client header would otherwise open.
    private static final Pattern SAFE_CHARS = Pattern.compile("[A-Za-z0-9-]+");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String supplied = req.getHeader(HEADER);
        String id = isValid(supplied) ? supplied : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        res.setHeader(HEADER, id);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private boolean isValid(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_LENGTH && SAFE_CHARS.matcher(value).matches();
    }
}
