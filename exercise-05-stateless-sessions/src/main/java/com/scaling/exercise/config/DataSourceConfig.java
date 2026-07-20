package com.scaling.exercise.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom DataSource configuration for read/write splitting.
 * Only active when the "replicas" Spring profile is enabled.
 *
 * Creates two connection pools:
 *   - PRIMARY: connects to postgres-primary (handles writes + reads)
 *   - REPLICA: connects to postgres-replica (handles read-only queries)
 *
 * Wraps them in a ReadWriteRoutingDataSource that routes based on
 * @Transactional(readOnly = true/false), then wraps THAT in a
 * LazyConnectionDataSourceProxy to ensure the routing decision
 * happens after Spring sets the transaction read-only flag.
 *
 * The result is transparent to the rest of the app — JPA, Hibernate,
 * and the repository layer don't know they're talking to different
 * databases. They just use "the DataSource" and the routing happens
 * behind the scenes.
 */
@Configuration
@Profile("replicas")
public class DataSourceConfig {

    @Value("${datasource.primary.jdbc-url}")
    private String primaryUrl;

    @Value("${datasource.primary.username}")
    private String primaryUsername;

    @Value("${datasource.primary.password}")
    private String primaryPassword;

    @Value("${datasource.primary.pool-size:10}")
    private int primaryPoolSize;

    @Value("${datasource.replica.jdbc-url}")
    private String replicaUrl;

    @Value("${datasource.replica.username}")
    private String replicaUsername;

    @Value("${datasource.replica.password}")
    private String replicaPassword;

    @Value("${datasource.replica.pool-size:10}")
    private int replicaPoolSize;

    @Bean
    @Primary
    public DataSource dataSource() {
        System.out.println("====================================================");
        System.out.println("[DataSourceConfig] Creating READ/WRITE routing DataSource");
        System.out.println("[DataSourceConfig] Primary: " + primaryUrl);
        System.out.println("[DataSourceConfig] Replica: " + replicaUrl);
        System.out.println("====================================================");

        // 1. Create separate connection pools for primary and replica
        DataSource primaryDs = createHikariDataSource(
                "Primary-HikariPool", primaryUrl, primaryUsername, primaryPassword, primaryPoolSize);
        DataSource replicaDs = createHikariDataSource(
                "Replica-HikariPool", replicaUrl, replicaUsername, replicaPassword, replicaPoolSize);

        // 2. Configure the routing DataSource
        ReadWriteRoutingDataSource routingDs = new ReadWriteRoutingDataSource();
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("primary", primaryDs);
        targetDataSources.put("replica", replicaDs);
        routingDs.setTargetDataSources(targetDataSources);
        routingDs.setDefaultTargetDataSource(primaryDs); // Fallback to primary if no transaction context

        // CRITICAL: Initialize the routing DataSource.
        // AbstractRoutingDataSource resolves target DataSources in afterPropertiesSet().
        // Without this call, Hibernate gets "DataSource router not initialized" on startup.
        routingDs.afterPropertiesSet();

        // 3. Wrap in LazyConnectionDataSourceProxy
        // This is CRITICAL. Without it, the connection is acquired before
        // @Transactional sets the readOnly flag, and everything goes to primary.
        return new LazyConnectionDataSourceProxy(routingDs);
    }

    private HikariDataSource createHikariDataSource(
            String poolName, String url, String username, String password, int poolSize) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName(poolName);
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(poolSize);
        ds.setMinimumIdle(5);
        ds.setConnectionTimeout(5000);
        ds.setIdleTimeout(30000);
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }
}
