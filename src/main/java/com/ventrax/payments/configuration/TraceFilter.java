package com.ventrax.payments.configuration;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

@Component
@Order(-200)
public class TraceFilter extends OncePerRequestFilter {
    private final Map<String, int[]> limits = new HashMap<>();
    private long minute;

    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain next)
            throws IOException, ServletException {
        String value = req.getHeader("X-Correlation-ID");
        UUID id;
        try {
            id = value == null ? UUID.randomUUID() : UUID.fromString(value);
        } catch (Exception e) {
            res.sendError(400);
            return;
        }
        res.setHeader("X-Correlation-ID", id.toString());
        res.setHeader("Cache-Control", "no-store");
        if (req.getRequestURI().startsWith("/api/v1/checkout/") && req.getMethod().equals("POST")) {
            synchronized (limits) {
                long now = System.currentTimeMillis() / 60000;
                if (minute != now) {
                    limits.clear();
                    minute = now;
                }
                if (limits.size() >= 10000
                        || ++limits.computeIfAbsent(req.getRemoteAddr(), k -> new int[1])[0] > 30) {
                    res.setHeader("Retry-After", "60");
                    res.sendError(429);
                    return;
                }
            }
        }
        if (req.getContentLengthLong() > 65536) {
            res.sendError(413);
            return;
        }
        MDC.put("correlationId", id.toString());
        try {
            next.doFilter(req, res);
        } finally {
            MDC.remove("correlationId");
        }
    }
}
