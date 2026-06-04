package com.scaling.exercise.controller;

import com.scaling.exercise.config.CacheMetrics;
import com.scaling.exercise.model.Product;
import com.scaling.exercise.service.ProductService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Same endpoints as Exercise 03, plus cache statistics.
 *
 * Every response includes:
 *   - X-Server-Id: which app server handled the request
 *   - X-Cache-Status: HIT or MISS (set by CacheHitInterceptor)
 *
 * New endpoint:
 *   - GET /api/products/cache-stats: shows hit/miss rates per cache
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final String serverId;

    public ProductController(ProductService productService) {
        this.productService = productService;
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        this.serverId = hostname;
    }

    @GetMapping
    public List<Product> getAllProducts(HttpServletResponse response) {
        response.setHeader("X-Server-Id", serverId);
        return productService.getAllProducts();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable Long id, HttpServletResponse response) {
        response.setHeader("X-Server-Id", serverId);
        return productService.getProductById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    public List<Product> searchProducts(@RequestParam("q") String query, HttpServletResponse response) {
        response.setHeader("X-Server-Id", serverId);
        return productService.searchProducts(query);
    }

    @GetMapping("/category/{category}")
    public List<Product> getByCategory(@PathVariable String category, HttpServletResponse response) {
        response.setHeader("X-Server-Id", serverId);
        return productService.getByCategory(category);
    }

    @GetMapping("/stats/{category}")
    public ProductService.ProductStats getCategoryStats(@PathVariable String category, HttpServletResponse response) {
        response.setHeader("X-Server-Id", serverId);
        return productService.computeCategoryStats(category);
    }

    @PostMapping
    public Product createProduct(@RequestBody Product product, HttpServletResponse response) {
        response.setHeader("X-Server-Id", serverId);
        return productService.createProduct(product);
    }

    /**
     * Cache statistics endpoint.
     * Shows hit/miss rates for each cache on THIS server.
     *
     * NOTE: Each app server has its own CacheMetrics counters.
     * The cache itself (Redis) is shared, but the hit/miss counts
     * are per-JVM. This is fine for observability — you can see
     * which server is getting more cache hits based on traffic distribution.
     */
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
        info.put("timestamp", System.currentTimeMillis());
        info.put("freeMemory", Runtime.getRuntime().freeMemory());
        info.put("totalMemory", Runtime.getRuntime().totalMemory());
        info.put("maxMemory", Runtime.getRuntime().maxMemory());
        info.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        info.put("cacheHitRate", String.format("%.1f%%", CacheMetrics.getHitRate()));
        return info;
    }
}
