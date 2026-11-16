package com.mitu.ai.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds standard browser security headers to every response: no MIME sniffing, no framing
 * (clickjacking), no referrer leaks, and a content security policy that only lets the page
 * load its own scripts, plus images from anywhere (AI pictures come from other sites).
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob: https:; media-src 'self' blob:; connect-src 'self'; "
            + "font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        res.setHeader("Permissions-Policy", "camera=(self), microphone=(self), geolocation=()");
        res.setHeader("Content-Security-Policy", CSP);
        chain.doFilter(req, res);
    }
}
