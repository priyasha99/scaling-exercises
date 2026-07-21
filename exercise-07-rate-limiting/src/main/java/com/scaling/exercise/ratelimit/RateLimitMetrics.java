package com.scaling.exercise.ratelimit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks rate limiting metrics — allowed, rejected, global vs per-user.
 * Same pattern as CacheMetrics and AsyncMetrics.
 */
public class RateLimitMetrics {

    private static final AtomicLong totalAllowed = new AtomicLong(0);
    private static final AtomicLong totalRejected = new AtomicLong(0);
    private static final AtomicLong userRejected = new AtomicLong(0);
    private static final AtomicLong ipRejected = new AtomicLong(0);
    private static final AtomicLong globalRejected = new AtomicLong(0);

    public static void recordAllowed() {
        totalAllowed.incrementAndGet();
    }

    public static void recordRejected(String tier) {
        totalRejected.incrementAndGet();
        switch (tier) {
            case "user" -> userRejected.incrementAndGet();
            case "ip" -> ipRejected.incrementAndGet();
            case "global" -> globalRejected.incrementAndGet();
        }
    }

    public static Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long allowed = totalAllowed.get();
        long rejected = totalRejected.get();
        long total = allowed + rejected;

        stats.put("totalAllowed", allowed);
        stats.put("totalRejected", rejected);
        stats.put("rejectionRate", total > 0
                ? String.format("%.2f%%", (double) rejected / total * 100)
                : "0.00%");
        stats.put("rejectedByUser", userRejected.get());
        stats.put("rejectedByIp", ipRejected.get());
        stats.put("rejectedByGlobal", globalRejected.get());
        return stats;
    }
}
