package com.scaling.exercise.sharding;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * DataSource configuration for database sharding.
 * Only active when sharding.enabled=true.
 *
 * Creates TWO HikariCP connection pools (one per shard) and wraps
 * them in a ShardRoutingDataSource that routes based on ShardContext.
 *
 * HOW IT REPLACES THE DEFAULT:
 * When sharding.enabled=false, Spring Boot auto-configures a single
 * DataSource from spring.datasource.* properties. When true, this
 * configuration creates a @Primary DataSource that overrides the
 * auto-configured one. The routing DataSource transparently handles
 * shard selection — JPA, Hibernate, and repositories don't know
 * they're talking to multiple databases.
 *
 * LazyConnectionDataSourceProxy defers connection acquisition until
 * the first SQL statement. This lets us set ShardContext inside a
 * @Transactional method body, after the transaction starts but before
 * the connection is actually acquired from the pool.
 *
 * SCHEMA INITIALIZATION:
 * Hibernate's ddl-auto only creates tables on the default shard (shard-0).
 * The shardSchemaInitializer bean creates tables on shard-1 using direct JDBC.
 * This runs as a CommandLineRunner at @Order(1), before DataSeeder at @Order(10).
 */
@Configuration
@ConditionalOnProperty(name = "sharding.enabled", havingValue = "true")
public class ShardDataSourceConfig {

    @Value("${sharding.shard-0.jdbc-url}")
    private String shard0Url;
    @Value("${sharding.shard-0.username}")
    private String shard0Username;
    @Value("${sharding.shard-0.password}")
    private String shard0Password;

    @Value("${sharding.shard-1.jdbc-url}")
    private String shard1Url;
    @Value("${sharding.shard-1.username}")
    private String shard1Username;
    @Value("${sharding.shard-1.password}")
    private String shard1Password;

    // Keep reference to shard-1 DataSource for schema initialization
    private HikariDataSource shard1DirectDs;

    @Bean
    public ShardRoutingDataSource shardRoutingDataSource() {
        System.out.println("====================================================");
        System.out.println("[ShardDataSourceConfig] Creating SHARDED routing DataSource");
        System.out.println("[ShardDataSourceConfig] Shard 0: " + shard0Url);
        System.out.println("[ShardDataSourceConfig] Shard 1: " + shard1Url);
        System.out.println("====================================================");

        HikariDataSource shard0Ds = createHikariDataSource(
                "Shard0-HikariPool", shard0Url, shard0Username, shard0Password);
        this.shard1DirectDs = createHikariDataSource(
                "Shard1-HikariPool", shard1Url, shard1Username, shard1Password);

        ShardRoutingDataSource routingDs = new ShardRoutingDataSource();
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("shard-0", shard0Ds);
        targetDataSources.put("shard-1", shard1DirectDs);
        routingDs.setTargetDataSources(targetDataSources);
        routingDs.setDefaultTargetDataSource(shard0Ds);

        // CRITICAL: Initialize the routing DataSource.
        // AbstractRoutingDataSource resolves target DataSources in afterPropertiesSet().
        routingDs.afterPropertiesSet();

        return routingDs;
    }

    @Bean
    @Primary
    public DataSource dataSource(ShardRoutingDataSource shardRoutingDataSource) {
        // LazyConnectionDataSourceProxy defers connection acquisition.
        // This lets ShardContext be set AFTER @Transactional starts but
        // BEFORE the actual JDBC connection is obtained.
        return new LazyConnectionDataSourceProxy(shardRoutingDataSource);
    }

    /**
     * Create the schema on shard-1 at startup.
     *
     * Hibernate's ddl-auto=update only runs against the default DataSource
     * target (shard-0). Shard-1 starts as an empty database. We create
     * the tables manually using direct JDBC before the DataSeeder runs.
     *
     * This mirrors a production pattern: schema migrations must run on
     * EVERY shard, not just the primary. Tools like Flyway have shard-aware
     * modes for this; here we do it explicitly.
     */
    @Bean
    @Order(1)
    public org.springframework.boot.CommandLineRunner shardSchemaInitializer() {
        return args -> {
            System.out.println("[ShardInit] Creating schema on shard-1...");
            try (Connection conn = shard1DirectDs.getConnection();
                 Statement stmt = conn.createStatement()) {

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS products (
                        id BIGSERIAL PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        description VARCHAR(1000),
                        price DECIMAL(19,2) NOT NULL,
                        category VARCHAR(255) NOT NULL,
                        stock_quantity INTEGER,
                        created_at TIMESTAMP
                    )
                """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id BIGSERIAL PRIMARY KEY,
                        username VARCHAR(255) UNIQUE NOT NULL,
                        password VARCHAR(255) NOT NULL,
                        role VARCHAR(50) NOT NULL
                    )
                """);

                System.out.println("[ShardInit] Schema created on shard-1");
            }
        };
    }

    private HikariDataSource createHikariDataSource(
            String poolName, String url, String username, String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName(poolName);
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(5);
        ds.setConnectionTimeout(5000);
        ds.setIdleTimeout(30000);
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }
}
