package com.scaling.exercise.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple cache hit/miss counter for observability.
 *
 * Tracks per-cache-name statistics so you can see which caches
 * are effective and which have poor hit rates.
 *
 * In production, you'd use Micrometer metrics with a Prometheus
 * exporter. This simple version lets us see the numbers in the
 * health endpoint and response headers without adding dependencies.
 */
public class CacheMetrics {

    private static final Map<String, AtomicLong> hits = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> misses = new ConcurrentHashMap<>();

    public static void recordHit(String cacheName) {
        hits.computeIfAbsent(cacheName, k -> new AtomicLong(0)).incrementAndGet();
    }

    public static void recordMiss(String cacheName) {
        misses.computeIfAbsent(cacheName, k -> new AtomicLong(0)).incrementAndGet();
    }

    public static long getHits(String cacheName) {
        return hits.getOrDefault(cacheName, new AtomicLong(0)).get();
    }

    public static long getMisses(String cacheName) {
        return misses.getOrDefault(cacheName, new AtomicLong(0)).get();
    }

    public static long getTotalHits() {
        return hits.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public static long getTotalMisses() {
        return misses.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public static double getHitRate() {
        long total = getTotalHits() + getTotalMisses();
        return total == 0 ? 0.0 : (double) getTotalHits() / total * 100;
    }

    public static Map<String, Map<String, Long>> getAllStats() {
        Map<String, Map<String, Long>> stats = new ConcurrentHashMap<>();
        for (String cacheName : hits.keySet()) {
            long h = getHits(cacheName);
            long m = getMisses(cacheName);
            stats.put(cacheName, Map.of(
                    "hits", h,
                    "misses", m,
                    "total", h + m,
                    "hitRate", (h + m) == 0 ? 0 : (h * 100) / (h + m)
            ));
        }
        // Add any caches that only have misses
        for (String cacheName : misses.keySet()) {
            if (!stats.containsKey(cacheName)) {
                long m = getMisses(cacheName);
                stats.put(cacheName, Map.of(
                        "hits", 0L,
                        "misses", m,
                        "total", m,
                        "hitRate", 0L
                ));
            }
        }
        return stats;
    }

    public static void reset() {
        hits.clear();
        misses.clear();
    }
}
