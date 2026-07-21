package com.scaling.exercise.sharding;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Monitoring endpoints for shard configuration and metrics.
 *
 * These endpoints are excluded from rate limiting and don't require auth.
 * Use them during load tests to verify shard distribution.
 */
@RestController
@RequestMapping("/api/shard")
public class ShardController {

    private final ShardingService shardingService;

    private static final String[] CATEGORIES = {
            "Electronics", "Books", "Clothing", "Home & Kitchen",
            "Sports", "Toys", "Health", "Automotive"
    };

    public ShardController(ShardingService shardingService) {
        this.shardingService = shardingService;
    }

    /**
     * Show current shard configuration and category-to-shard mapping.
     *
     * This is the first thing to check after starting the stack — it tells
     * you which categories live on which shard.
     */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", shardingService.isEnabled());
        config.put("shardCount", shardingService.getShardCount());
        config.put("algorithm", "hash-based (category.hashCode() % shardCount)");
        config.put("shardKey", "category");

        // Show which categories map to which shard
        Map<String, Object> mapping = new LinkedHashMap<>();
        Map<String, java.util.List<String>> shardCategories = new LinkedHashMap<>();
        for (int i = 0; i < shardingService.getShardCount(); i++) {
            shardCategories.put("shard-" + i, new java.util.ArrayList<>());
        }

        for (String cat : CATEGORIES) {
            int shard = shardingService.getShardForCategory(cat);
            String shardId = "shard-" + shard;
            mapping.put(cat, shardId);
            shardCategories.get(shardId).add(cat);
        }

        config.put("categoryMapping", mapping);
        config.put("categoriesPerShard", shardCategories);

        return config;
    }

    /**
     * Show query distribution across shards.
     *
     * During a load test, check this to see if queries are evenly distributed.
     * A large skew means some categories are queried more than others (hot spot).
     */
    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        return ShardMetrics.getStats();
    }
}
