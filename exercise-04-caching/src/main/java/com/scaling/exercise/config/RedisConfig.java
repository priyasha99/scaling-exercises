package com.scaling.exercise.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis cache configuration.
 *
 * KEY CONCEPTS:
 *
 * 1. SERIALIZATION: Objects must be converted to bytes to store in Redis.
 *    We use Jackson (JSON) instead of Java serialization because:
 *    - JSON is human-readable (you can inspect cache contents with redis-cli)
 *    - JSON is language-agnostic (other services could read the cache)
 *    - Java serialization is fragile (breaks if you rename a field)
 *
 * 2. TTL (Time-To-Live): How long cached data stays valid.
 *    - Too short: cache misses too often, not much benefit
 *    - Too long: stale data served to users
 *    - Different endpoints get different TTLs based on how frequently
 *      the underlying data changes
 *
 * 3. CACHE NAMES: Each cache is a separate namespace in Redis.
 *    "products::Electronics" is different from "search::Electronics".
 *    This lets us invalidate specific caches without clearing everything.
 */
@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        System.out.println("====================================================");
        System.out.println("[RedisConfig] Configuring Redis cache manager");
        System.out.println("====================================================");

        // Jackson ObjectMapper configured for Redis serialization
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Enable type info so Jackson knows what class to deserialize into
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(mapper);

        // Default cache config: 5 minute TTL
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer))
                .disableCachingNullValues();

        // Per-cache TTL overrides
        // Different data has different staleness tolerance
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();

        // Product by ID — rarely changes, cache for 10 minutes
        cacheConfigs.put("product", defaultConfig.entryTtl(Duration.ofMinutes(10)));

        // Category listings — moderate change rate, 5 minutes
        cacheConfigs.put("products_by_category", defaultConfig.entryTtl(Duration.ofMinutes(5)));

        // Search results — keyword-based, 3 minutes (new products might match)
        cacheConfigs.put("search", defaultConfig.entryTtl(Duration.ofMinutes(3)));

        // Category stats — expensive to compute, 5 minutes
        cacheConfigs.put("category_stats", defaultConfig.entryTtl(Duration.ofMinutes(5)));

        // All products list — changes with every write, 2 minutes
        cacheConfigs.put("all_products", defaultConfig.entryTtl(Duration.ofMinutes(2)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }
}
