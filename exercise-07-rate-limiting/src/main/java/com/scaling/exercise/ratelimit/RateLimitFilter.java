package com.scaling.exercise.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rate limiting filter — runs AFTER JWT authentication, BEFORE controllers.
 *
 * ORDER OF CHECKS:
 *   1. Global rate limit (system-wide backpressure)
 *   2. Per-user or per-IP rate limit (individual fairness)
 *
 * If EITHER limit is exceeded, the request gets 429 Too Many Requests.
 * The global check runs first because it's the cheapest rejection —
 * no point checking per-user limits if the system is already overloaded.
 *
 * RESPONSE HEADERS (standard rate limit headers):
 *   X-RateLimit-Limit:     bucket capacity
 *   X-RateLimit-Remaining: tokens left in bucket
 *   X-RateLimit-Reset:     seconds until bucket is full
 *   Retry-After:           seconds to wait before retrying (on 429 only)
 *
 * WHY AFTER JWT FILTER?
 * We need the authenticated username to do per-user rate limiting.
 * The JWT filter runs first, populates SecurityContext, then this
 * filter reads the username from the context. Unauthenticated requests
 * fall back to per-IP limiting.
 *
 * EXCLUDED PATHS:
 * Health and monitoring endpoints are excluded — you don't want
 * rate limiting to block your health checks or monitoring dashboards.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterService rateLimiterService;
    private final RateLimitConfig config;

    public RateLimitFilter(RateLimiterService rateLimiterService, RateLimitConfig config) {
        this.rateLimiterService = rateLimiterService;
        this.config = config;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Don't rate limit health checks, metrics, or monitoring endpoints
        return path.equals("/api/products/health")
                || path.equals("/api/products/cache-stats")
                || path.equals("/api/products/async/metrics")
                || path.startsWith("/api/rate-limit/")
                || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!config.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // --- Check 1: Global rate limit ---
        RateLimitResult globalResult = rateLimiterService.isAllowed(
                "rate_limit::global",
                config.getGlobalCapacity(),
                config.getGlobalRefillRate()
        );

        if (!globalResult.isAllowed()) {
            RateLimitMetrics.recordRejected("global");
            sendRateLimitResponse(response, globalResult, config.getGlobalCapacity(), "global");
            return;
        }

        // --- Check 2: Per-user or per-IP rate limit ---
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        RateLimitResult result;
        int capacity;
        String tier;

        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            // Authenticated — per-user limit
            String username = auth.getName();
            boolean isAdmin = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(a -> a.equals("ROLE_ADMIN"));

            if (isAdmin) {
                capacity = config.getAdminCapacity();
                result = rateLimiterService.isAllowed(
                        "rate_limit::user::" + username,
                        config.getAdminCapacity(),
                        config.getAdminRefillRate()
                );
            } else {
                capacity = config.getUserCapacity();
                result = rateLimiterService.isAllowed(
                        "rate_limit::user::" + username,
                        config.getUserCapacity(),
                        config.getUserRefillRate()
                );
            }
            tier = "user";
        } else {
            // Unauthenticated — per-IP limit
            String clientIp = getClientIp(request);
            capacity = config.getIpCapacity();
            result = rateLimiterService.isAllowed(
                    "rate_limit::ip::" + clientIp,
                    config.getIpCapacity(),
                    config.getIpRefillRate()
            );
            tier = "ip";
        }

        if (!result.isAllowed()) {
            RateLimitMetrics.recordRejected(tier);
            sendRateLimitResponse(response, result, capacity, tier);
            return;
        }

        // Request allowed — set rate limit headers and continue
        RateLimitMetrics.recordAllowed();
        setRateLimitHeaders(response, result, capacity);
        filterChain.doFilter(request, response);
    }

    /**
     * Send 429 Too Many Requests response.
     *
     * Includes Retry-After header — the client should wait this many
     * seconds before trying again. Well-behaved clients respect this
     * and implement exponential backoff.
     */
    private void sendRateLimitResponse(HttpServletResponse response,
                                        RateLimitResult result,
                                        int capacity,
                                        String tier) throws IOException {
        long retryAfterSeconds = Math.max(1, (result.getRetryAfterMs() + 999) / 1000);

        response.setStatus(429);
        response.setContentType("application/json");
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setHeader("X-RateLimit-Tier", tier);

        String body = String.format(
                "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded (%s)\",\"retryAfterSeconds\":%d,\"tier\":\"%s\"}",
                tier, retryAfterSeconds, tier
        );
        response.getWriter().write(body);
    }

    /**
     * Set standard rate limit headers on successful responses.
     * These help clients self-regulate their request rate.
     */
    private void setRateLimitHeaders(HttpServletResponse response,
                                      RateLimitResult result,
                                      int capacity) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.getTokensRemaining()));
    }

    /**
     * Extract client IP, respecting X-Forwarded-For from Nginx.
     */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs: client, proxy1, proxy2
            // The first one is the original client
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
