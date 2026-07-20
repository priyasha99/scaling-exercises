package com.scaling.exercise.messaging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks async processing metrics — published, consumed, failed counts.
 * Same pattern as CacheMetrics from Exercise 04.
 */
public class AsyncMetrics {

    private static final AtomicLong published = new AtomicLong(0);
    private static final AtomicLong consumed = new AtomicLong(0);
    private static final AtomicLong failed = new AtomicLong(0);
    private static final AtomicLong totalProcessingTimeMs = new AtomicLong(0);

    public static void recordPublished() { published.incrementAndGet(); }
    public static void recordConsumed(long processingTimeMs) {
        consumed.incrementAndGet();
        totalProcessingTimeMs.addAndGet(processingTimeMs);
    }
    public static void recordFailed() { failed.incrementAndGet(); }

    public static Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long pub = published.get();
        long con = consumed.get();
        long fail = failed.get();
        long totalTime = totalProcessingTimeMs.get();

        stats.put("published", pub);
        stats.put("consumed", con);
        stats.put("failed", fail);
        stats.put("pending", pub - con - fail);
        stats.put("avgProcessingTimeMs", con > 0 ? totalTime / con : 0);
        return stats;
    }
}
