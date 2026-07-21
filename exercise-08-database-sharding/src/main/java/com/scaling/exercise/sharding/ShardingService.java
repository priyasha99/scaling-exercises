package com.scaling.exercise.sharding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Determines which shard a piece of data belongs to.
 *
 * ALGORITHM: Hash-based sharding on the category field.
 *   shard = Math.abs(category.hashCode()) % shardCount
 *
 * WHY CATEGORY AS SHARD KEY?
 * - Category-specific queries (stats, listing) hit ONE shard
 * - Products in the same category are co-located
 * - 8 categories across 2 shards gives reasonable distribution
 *
 * WHY HASH-BASED?
 * - Even distribution (no hot spots from alphabetical clustering)
 * - Deterministic (same category always goes to same shard)
 * - No lookup table needed (shard is computed, not stored)
 *
 * TRADE-OFF:
 * Cross-shard queries (search, get all) must fan out to every shard.
 * This is the fundamental cost of sharding — you trade single-shard
 * efficiency for horizontal scalability.
 */
@Service
public class ShardingService {

    private final boolean enabled;
    private final int shardCount;

    public ShardingService(
            @Value("${sharding.enabled:false}") boolean enabled,
            @Value("${sharding.shard-count:2}") int shardCount) {
        this.enabled = enabled;
        this.shardCount = shardCount;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getShardCount() {
        return shardCount;
    }

    /**
     * Determine which shard a category belongs to.
     *
     * Uses Java's String.hashCode() modulo shard count.
     * This is deterministic — "Electronics" always maps to the same shard
     * regardless of which server computes it.
     */
    public int getShardForCategory(String category) {
        if (!enabled || category == null) return 0;
        return Math.abs(category.hashCode()) % shardCount;
    }

    public String getShardId(int shardIndex) {
        return "shard-" + shardIndex;
    }

    public String getShardIdForCategory(String category) {
        return getShardId(getShardForCategory(category));
    }
}
