package com.scaling.exercise.sharding;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks query distribution across shards.
 *
 * In a well-configured sharding setup, queries should be roughly
 * evenly distributed. If one shard gets significantly more queries,
 * it indicates a hot spot — some shard keys are more popular.
 *
 * Cross-shard queries are tracked separately. A high cross-shard
 * count means the workload isn't a good fit for the shard key,
 * or the API needs shard-aware endpoints.
 */
public class ShardMetrics {

    private static final AtomicLong shard0Queries = new AtomicLong(0);
    private static final AtomicLong shard1Queries = new AtomicLong(0);
    private static final AtomicLong crossShardQueries = new AtomicLong(0);

    public static void recordQuery(int shardIndex) {
        if (shardIndex == 0) shard0Queries.incrementAndGet();
        else shard1Queries.incrementAndGet();
    }

    public static void recordCrossShardQuery() {
        crossShardQueries.incrementAndGet();
    }

    public static Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long s0 = shard0Queries.get();
        long s1 = shard1Queries.get();
        long total = s0 + s1;

        stats.put("shard0Queries", s0);
        stats.put("shard1Queries", s1);
        stats.put("crossShardQueries", crossShardQueries.get());
        stats.put("totalQueries", total);

        if (total > 0) {
            stats.put("shard0Percentage", String.format("%.1f%%", s0 * 100.0 / total));
            stats.put("shard1Percentage", String.format("%.1f%%", s1 * 100.0 / total));
        }

        return stats;
    }
}
