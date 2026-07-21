package com.scaling.exercise.sharding;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Routes database connections to the correct shard based on ShardContext.
 *
 * Extends AbstractRoutingDataSource — the same base class we used for
 * read/write routing in Exercise 03. The difference:
 *   - Exercise 03: routes based on readOnly flag → primary vs replica
 *   - Exercise 08: routes based on ShardContext → shard-0 vs shard-1
 *
 * HOW IT WORKS:
 * 1. ShardContext.setCurrentShard("shard-1") is called before a DB query
 * 2. AbstractRoutingDataSource calls our determineCurrentLookupKey()
 * 3. We return "shard-1"
 * 4. AbstractRoutingDataSource looks up the DataSource mapped to "shard-1"
 * 5. The query executes against that shard's PostgreSQL instance
 *
 * If no shard is set (ShardContext is null), we default to shard-0.
 * This handles cases like user authentication (not sharded).
 */
public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        String shard = ShardContext.getCurrentShard();
        if (shard == null) {
            shard = "shard-0";
        }

        // Track routing for metrics
        try {
            int idx = Integer.parseInt(shard.replace("shard-", ""));
            ShardMetrics.recordQuery(idx);
        } catch (NumberFormatException e) {
            // ignore
        }

        return shard;
    }
}
