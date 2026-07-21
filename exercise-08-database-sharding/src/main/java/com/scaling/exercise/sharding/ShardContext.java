package com.scaling.exercise.sharding;

/**
 * Thread-local holder for the current shard identifier.
 *
 * HOW IT WORKS:
 * Before any database operation, the calling code sets the shard:
 *   ShardContext.setCurrentShard("shard-0");
 *
 * The ShardRoutingDataSource reads this value to determine which
 * PostgreSQL instance to route the connection to.
 *
 * MUST be cleared after use (in a finally block) to prevent
 * shard leaking between requests on the same thread.
 *
 * This is the same pattern as Spring's TransactionSynchronizationManager
 * — a thread-local that controls routing behavior. The difference is
 * that TransactionSynchronizationManager routes between primary/replica,
 * while ShardContext routes between shard-0/shard-1.
 */
public class ShardContext {

    private static final ThreadLocal<String> currentShard = new ThreadLocal<>();

    public static void setCurrentShard(String shardId) {
        currentShard.set(shardId);
    }

    public static String getCurrentShard() {
        return currentShard.get();
    }

    public static void clear() {
        currentShard.remove();
    }
}
