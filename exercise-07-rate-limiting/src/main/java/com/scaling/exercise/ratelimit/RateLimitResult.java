package com.scaling.exercise.ratelimit;

/**
 * Result of a rate limit check.
 *
 * The Lua script returns three values:
 *   [0] = allowed (0 or 1)
 *   [1] = tokens remaining in the bucket
 *   [2] = retry_after_ms (0 if allowed, >0 if rejected)
 *
 * This POJO wraps those values for use in the filter.
 */
public class RateLimitResult {

    private final boolean allowed;
    private final long tokensRemaining;
    private final long retryAfterMs;

    public RateLimitResult(boolean allowed, long tokensRemaining, long retryAfterMs) {
        this.allowed = allowed;
        this.tokensRemaining = tokensRemaining;
        this.retryAfterMs = retryAfterMs;
    }

    public boolean isAllowed() { return allowed; }
    public long getTokensRemaining() { return tokensRemaining; }
    public long getRetryAfterMs() { return retryAfterMs; }
}
