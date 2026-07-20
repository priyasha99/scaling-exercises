package com.scaling.exercise.messaging;

import com.scaling.exercise.model.Product;
import com.scaling.exercise.repository.ProductRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/**
 * Consumes product creation messages from RabbitMQ.
 *
 * THIS IS WHERE THE ACTUAL WORK HAPPENS.
 * The API published the message and returned 202 to the user.
 * Now, in the background, this consumer:
 *   1. Picks up the message from the queue
 *   2. Saves the product to PostgreSQL
 *   3. Evicts relevant caches
 *   4. Tracks the result for the status endpoint
 *
 * CONCURRENCY:
 * Each app server runs 3-5 consumer threads (configured in properties).
 * With 3 app servers, that's 9-15 consumers processing messages in
 * parallel. RabbitMQ distributes messages round-robin across consumers.
 *
 * RETRY LOGIC (Part C):
 * If processing fails (DB down, constraint violation, etc.):
 *   - Retry up to 3 times with increasing delay
 *   - After 3 failures, reject the message → goes to DLQ
 *   - DLQ messages can be inspected and replayed
 */
@Service
public class ProductConsumer {

    private final ProductRepository productRepository;
    private final CacheManager cacheManager;
    private final StringRedisTemplate redisTemplate;
    private final String serverId;

    // Max retries before sending to DLQ
    private static final int MAX_RETRIES = 3;

    // Redis key prefix for tracking async request status
    private static final String STATUS_PREFIX = "async_status::";

    public ProductConsumer(ProductRepository productRepository,
                           CacheManager cacheManager,
                           StringRedisTemplate redisTemplate) {
        this.productRepository = productRepository;
        this.cacheManager = cacheManager;
        this.redisTemplate = redisTemplate;
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        this.serverId = hostname;
    }

    /**
     * Listen for product creation messages.
     *
     * @RabbitListener makes this method a consumer. Spring AMQP
     * automatically deserializes the JSON message back to a
     * ProductMessage object (using the Jackson converter we configured).
     *
     * ackMode = "AUTO" means:
     *   - If the method returns normally → message is acknowledged (removed from queue)
     *   - If the method throws → message is rejected (goes to DLQ)
     */
    @RabbitListener(queues = RabbitConfig.PRODUCT_QUEUE)
    @Transactional
    public void handleProductCreation(ProductMessage message) {
        long startTime = System.currentTimeMillis();
        String requestId = message.getRequestId();

        try {
            System.out.println("[Consumer] Processing: " + message.getName() +
                    " (requestId: " + requestId + ", retry: " + message.getRetryCount() +
                    ") on server: " + serverId);

            // Update status to PROCESSING
            updateStatus(requestId, "PROCESSING", serverId);

            // Create and save the product
            Product product = new Product(
                    message.getName(),
                    message.getDescription(),
                    message.getPrice(),
                    message.getCategory(),
                    message.getStockQuantity()
            );

            Product saved = productRepository.save(product);

            // Evict related caches (same logic as sync createProduct)
            evictCaches(message.getCategory());

            long duration = System.currentTimeMillis() - startTime;

            // Update status to COMPLETED
            updateStatus(requestId, "COMPLETED", serverId);

            System.out.println("[Consumer] Completed: " + saved.getName() +
                    " (id: " + saved.getId() + ", took: " + duration + "ms) on server: " + serverId);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;

            System.err.println("[Consumer] FAILED: " + message.getName() +
                    " (requestId: " + requestId + ", retry: " + message.getRetryCount() +
                    ", error: " + e.getMessage() + ", took: " + duration + "ms)");

            // Update status to FAILED
            updateStatus(requestId, "FAILED: " + e.getMessage(), serverId);

            // Re-throw to trigger DLQ (after Spring's retry is exhausted)
            throw e;
        }
    }

    /**
     * Evict caches affected by the new product.
     * Same eviction logic as the sync createProduct in ProductService.
     */
    private void evictCaches(String category) {
        evictFromCache("all_products", "all");
        evictFromCache("products_by_category", category);
        evictFromCache("category_stats", category);

        // Evict all search results (can't know which match the new product)
        Cache searchCache = cacheManager.getCache("search");
        if (searchCache != null) {
            searchCache.clear();
        }
    }

    private void evictFromCache(String cacheName, String key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }

    /**
     * Store async request status in Redis for the status endpoint.
     *
     * The client got a 202 Accepted with a requestId. They can poll
     * GET /api/products/async/status/{requestId} to check progress.
     *
     * Status values: QUEUED → PROCESSING → COMPLETED or FAILED
     * TTL: 10 minutes (auto-cleanup)
     */
    private void updateStatus(String requestId, String status, String processedBy) {
        if (requestId != null) {
            redisTemplate.opsForValue().set(
                    STATUS_PREFIX + requestId,
                    status + "|" + processedBy,
                    java.time.Duration.ofMinutes(10)
            );
        }
    }
}
