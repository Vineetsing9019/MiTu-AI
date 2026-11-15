package com.mitu.ai.web;

/** Lets tests outside this package drive the rate limiter's clock. */
public class RateLimitFilterAccess {
    private final RateLimitFilter filter;

    public RateLimitFilterAccess(int perMinute) {
        this.filter = new RateLimitFilter(perMinute);
    }

    public boolean take(String key, long nowNanos) {
        return filter.tryTake(key, nowNanos);
    }
}
