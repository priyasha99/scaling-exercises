package com.scaling.exercise.controller;

import com.scaling.exercise.model.Product;
import com.scaling.exercise.service.ProductService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/**
 * Same endpoints as Exercise 01/02.
 *
 * NEW: Every response includes an "X-Server-Id" header showing
 * which app server handled the request. This lets you verify that
 * the load balancer distributes requests AND that data is consistent
 * across servers (because they all share one database now).
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final String serverId;

    public ProductController(ProductService productService) {
        this.productService = productService;
        // Get container hostname — each Docker container has a unique one
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
     * Health check — includes server ID and database info.
     * Useful for verifying which server you're talking to.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "serverId", serverId,
                "timestamp", System.currentTimeMillis(),
                "freeMemory", Runtime.getRuntime().freeMemory(),
                "totalMemory", Runtime.getRuntime().totalMemory(),
                "maxMemory", Runtime.getRuntime().maxMemory(),
                "availableProcessors", Runtime.getRuntime().availableProcessors()
        );
    }
}
