package com.mitu.ai.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protects the free AI quota on a public deployment: each visitor (by IP) gets a
 * small bucket of AI requests that refills over time (token bucket algorithm).
 * Only the endpoints that call the AI are limited.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final class Bucket {
        double tokens;
        long last;

        Bucket(double tokens, long last) {
            this.tokens = tokens;
            this.last = last;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final double refillPerSecond;

    public RateLimitFilter(@Value("${mitu.rate-limit.per-minute:20}") int perMinute) {
        this.capacity = Math.max(1, perMinute);
        this.refillPerSecond = perMinute / 60.0;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        boolean aiCall = "POST".equals(req.getMethod())
                && (path.equals("/api/chat") || path.equals("/api/art") || path.equals("/api/documents"));
        return !aiCall;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (tryTake(clientIp(req), System.nanoTime())) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(429);
        res.setContentType("application/json");
        res.getWriter().write("{\"message\":\"Too many requests. Please wait a minute and try again.\"}");
    }

    boolean tryTake(String key, long nowNanos) {
        if (buckets.size() > 10_000) buckets.clear(); // keep memory bounded
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, nowNanos));
        synchronized (b) {
            double seconds = (nowNanos - b.last) / 1e9;
            b.tokens = Math.min(capacity, b.tokens + seconds * refillPerSecond);
            b.last = nowNanos;
            if (b.tokens < 1) return false;
            b.tokens -= 1;
            return true;
        }
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For"); // set by Render / proxies
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
    }
}
