package com.scaling.exercise.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Routes database connections based on transaction type:
 *   - @Transactional(readOnly = true)  → "replica"
 *   - @Transactional                   → "primary"
 *
 * How it works:
 * 1. Spring's @Transactional interceptor sets a thread-local flag
 *    via TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
 * 2. When JPA needs a connection, it calls getConnection() on this DataSource
 * 3. AbstractRoutingDataSource calls our determineCurrentLookupKey()
 * 4. We check the read-only flag and return "replica" or "primary"
 * 5. AbstractRoutingDataSource looks up the corresponding DataSource
 *    from the target map we configured in DataSourceConfig
 *
 * IMPORTANT: This only works when wrapped in LazyConnectionDataSourceProxy.
 * Without it, the connection is acquired BEFORE the @Transactional interceptor
 * sets the read-only flag, so we'd always see readOnly=false and route everything
 * to the primary. LazyConnectionDataSourceProxy defers connection acquisition
 * until the first SQL statement, by which point the flag is set correctly.
 */
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    private final AtomicLong primaryCount = new AtomicLong(0);
    private final AtomicLong replicaCount = new AtomicLong(0);

    @Override
    protected Object determineCurrentLookupKey() {
        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        String target = isReadOnly ? "replica" : "primary";

        // Count routing decisions for monitoring
        if (isReadOnly) {
            long count = replicaCount.incrementAndGet();
            if (count <= 5 || count % 500 == 0) {
                System.out.println("[DataSource Routing] → REPLICA (total: replica=" + count + ", primary=" + primaryCount.get() + ")");
            }
        } else {
            long count = primaryCount.incrementAndGet();
            if (count <= 5 || count % 500 == 0) {
                System.out.println("[DataSource Routing] → PRIMARY (total: replica=" + replicaCount.get() + ", primary=" + count + ")");
            }
        }

        return target;
    }
}
