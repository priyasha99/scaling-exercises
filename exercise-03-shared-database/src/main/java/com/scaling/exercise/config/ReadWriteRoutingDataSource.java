package com.scaling.exercise.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    @Override
    protected Object determineCurrentLookupKey() {
        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        String target = isReadOnly ? "replica" : "primary";

        // Log routing decisions (useful for debugging, disable in production)
        if (System.getProperty("log.datasource.routing", "false").equals("true")) {
            System.out.println("[DataSource Routing] readOnly=" + isReadOnly + " → " + target);
        }

        return target;
    }
}
