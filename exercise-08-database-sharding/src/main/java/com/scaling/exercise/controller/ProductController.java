package com.scaling.exercise.controller;

import com.scaling.exercise.config.CacheMetrics;
import com.scaling.exercise.messaging.AsyncMetrics;
import com.scaling.exercise.messaging.ProductMessage;
import com.scaling.exercise.messaging.ProductProducer;
import com.scaling.exercise.model.Product;
import com.scaling.exercise.service.ProductService;
import com.scaling.exercise.sharding.ShardingService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Product endpoints — now with SYNC and ASYNC modes.
 *
 * EXERCISE 06 CHANGES:
 * - POST /api/products works in sync OR async mode (toggle via app.async-enabled)
 * - POST /api/products/async → always publishes to RabbitMQ, returns 202 Accepted
 * - GET /api/products/async/status/{requestId} → poll processing status
 * - GET /api/products/async/metrics → async processing stats
 *
 * SYNC FLOW (Exercise 05):
 *   POST → validate → INSERT into DB → evict caches → return 201 (50-200ms)
 *
 * ASYNC FLOW (Exercise 06):
 *   POST → validate → publish to RabbitMQ (~1ms) → return 202 Accepted
 *   Background: consumer picks up message → INSERT → evict caches
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final ProductProducer productProducer;
    private final StringRedisTemplate redisTemplate;
    private final ShardingService shardingService;
    private final String serverId;
    private final boolean asyncEnabled;

    public ProductController(ProductService productService,
                             ProductProducer productProducer,
                             StringRedisTemplate redisTemplate,
                             ShardingService shardingService,
                             @Value("${app.async-enabled:true}") boolean asyncEnabled) {
        this.productService = productService;
        this.productProducer = productProducer;
        this.redisTemplate = redisTemplate;
        this.shardingService = shardingService;
        this.asyncEnabled = asyncEnabled;
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        this.serverId = hostname;
    }

    private void setCacheHeaders(HttpServletResponse response) {
        response.setHeader("X-Server-Id", serverId);
        String cacheStatus = ProductService.getCacheStatus();
        if (!"NONE".equals(cacheStatus)) {
            response.setHeader("X-Cache-Status", cacheStatus);
        }
    }

    // -------------------------------------------------------
    // READ endpoints (unchanged from Exercise 05)
    // -------------------------------------------------------

    @GetMapping
    public List<Product> getAllProducts(HttpServletResponse response) {
        ProductService.prepareCacheTracking();
        List<Product> result = productService.getAllProducts();
        setCacheHeaders(response);
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable Long id, HttpServletResponse response) {
        ProductService.prepareCacheTracking();
        Product product = productService.getProductById(id);
        setCacheHeaders(response);
        if (product != null) {
            return ResponseEntity.ok(product);
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/search")
    public List<Product> searchProducts(@RequestParam("q") String query, HttpServletResponse response) {
        ProductService.prepareCacheTracking();
        List<Product> result = productService.searchProducts(query);
        setCacheHeaders(response);
        return result;
    }

    @GetMapping("/category/{category}")
    public List<Product> getByCategory(@PathVariable String category, HttpServletResponse response) {
        ProductService.prepareCacheTracking();
        List<Product> result = productService.getByCategory(category);
        setCacheHeaders(response);
        return result;
    }

    @GetMapping("/stats/{category}")
    public ProductService.ProductStats getCategoryStats(@PathVariable String category, HttpServletResponse response) {
        ProductService.prepareCacheTracking();
        ProductService.ProductStats result = productService.computeCategoryStats(category);
        setCacheHeaders(response);
        return result;
    }

    // -------------------------------------------------------
    // WRITE endpoints — sync vs async
    // -------------------------------------------------------

    /**
     * Product creation — sync or async depending on toggle.
     *
     * When app.async-enabled=false: direct DB insert, returns 201.
     * When app.async-enabled=true: publishes to RabbitMQ, returns 202.
     *
     * This toggle lets you benchmark the SAME endpoint in both modes
     * without changing the load test scripts.
     */
    @PostMapping
    public ResponseEntity<?> createProduct(@RequestBody Product product,
                                           Authentication auth,
                                           HttpServletResponse response) {
        response.setHeader("X-Server-Id", serverId);

        if (asyncEnabled) {
            return createProductAsync(product, auth);
        }

        // Sync path — same as Exercise 05
        Product saved = productService.createProduct(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Explicit async endpoint — always async regardless of toggle.
     *
     * POST /api/products/async
     *
     * Returns 202 Accepted with:
     *   - requestId: unique ID to track this request
     *   - status: "QUEUED"
     *   - statusUrl: where to poll for progress
     *   - publishedBy: which server accepted the request
     */
    @PostMapping("/async")
    public ResponseEntity<?> createProductExplicitAsync(@RequestBody Product product,
                                                        Authentication auth,
                                                        HttpServletResponse response) {
        response.setHeader("X-Server-Id", serverId);
        return createProductAsync(product, auth);
    }

    /**
     * Shared async logic — publish to RabbitMQ and return 202.
     */
    private ResponseEntity<?> createProductAsync(Product product, Authentication auth) {
        String requestId = UUID.randomUUID().toString();
        String username = (auth != null) ? auth.getName() : "anonymous";

        ProductMessage message = new ProductMessage(
                requestId,
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getCategory(),
                product.getStockQuantity(),
                username
        );

        // Publish to RabbitMQ (~1ms)
        productProducer.publishProductCreation(message);
        AsyncMetrics.recordPublished();

        // Store initial status in Redis
        redisTemplate.opsForValue().set(
                "async_status::" + requestId,
                "QUEUED|" + serverId,
                java.time.Duration.ofMinutes(10)
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("status", "QUEUED");
        result.put("statusUrl", "/api/products/async/status/" + requestId);
        result.put("publishedBy", serverId);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    // -------------------------------------------------------
    // ASYNC status and metrics endpoints
    // -------------------------------------------------------

    /**
     * Poll the status of an async product creation request.
     *
     * GET /api/products/async/status/{requestId}
     *
     * Status progression: QUEUED → PROCESSING → COMPLETED or FAILED
     * Status entries auto-expire after 10 minutes.
     */
    @GetMapping("/async/status/{requestId}")
    public ResponseEntity<?> getAsyncStatus(@PathVariable String requestId) {
        String statusValue = redisTemplate.opsForValue().get("async_status::" + requestId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);

        if (statusValue == null) {
            result.put("status", "NOT_FOUND");
            result.put("message", "Request not found or expired (TTL: 10 minutes)");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }

        // Format: "STATUS|serverId"
        String[] parts = statusValue.split("\\|", 2);
        result.put("status", parts[0]);
        if (parts.length > 1) {
            result.put("processedBy", parts[1]);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Async processing metrics — published, consumed, failed, pending.
     */
    @GetMapping("/async/metrics")
    public Map<String, Object> asyncMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverId", serverId);
        result.put("asyncEnabled", asyncEnabled);
        result.put("metrics", AsyncMetrics.getStats());
        return result;
    }

    // -------------------------------------------------------
    // Existing monitoring endpoints
    // -------------------------------------------------------

    @GetMapping("/cache-stats")
    public Map<String, Object> cacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("serverId", serverId);
        stats.put("totalHits", CacheMetrics.getTotalHits());
        stats.put("totalMisses", CacheMetrics.getTotalMisses());
        stats.put("hitRate", String.format("%.1f%%", CacheMetrics.getHitRate()));
        stats.put("caches", CacheMetrics.getAllStats());
        return stats;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "UP");
        info.put("serverId", serverId);
        info.put("asyncEnabled", asyncEnabled);
        info.put("shardingEnabled", shardingService.isEnabled());
        info.put("timestamp", System.currentTimeMillis());
        info.put("freeMemory", Runtime.getRuntime().freeMemory());
        info.put("totalMemory", Runtime.getRuntime().totalMemory());
        info.put("maxMemory", Runtime.getRuntime().maxMemory());
        info.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        info.put("cacheHitRate", String.format("%.1f%%", CacheMetrics.getHitRate()));
        return info;
    }
}
