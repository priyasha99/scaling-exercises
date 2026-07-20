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
 * Product endpoints — same as Exercise 04 with caching.
 *
 * EXERCISE 05 CHANGES:
 * - GET endpoints are public (no token needed) — read-only data
 * - POST endpoint requires authentication (valid JWT)
 * - X-Server-Id header shows which server handled each request
 * - X-Auth-User header (set by JwtAuthFilter) shows the authenticated user
 *
 * Combined with X-Server-Id, you can verify that:
 * 1. The same user's requests are handled by DIFFERENT servers (no sticky sessions)
 * 2. Authentication works regardless of which server handles the request
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

    /**
     * Sets both X-Server-Id and X-Cache-Status headers.
     * Must be called AFTER the service method returns but BEFORE
     * returning the result (so headers are set before body is written).
     */
    private void setCacheHeaders(HttpServletResponse response) {
        response.setHeader("X-Server-Id", serverId);
        String cacheStatus = ProductService.getCacheStatus();
        if (!"NONE".equals(cacheStatus)) {
            response.setHeader("X-Cache-Status", cacheStatus);
        }
    }

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

    @PostMapping
    public Product createProduct(@RequestBody Product product, HttpServletResponse response) {
        response.setHeader("X-Server-Id", serverId);
        return productService.createProduct(product);
    }

    /**
     * Cache statistics endpoint.
     * Shows hit/miss rates for each cache on THIS server.
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
