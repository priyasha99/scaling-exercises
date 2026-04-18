package com.scaling.exercise.controller;

import com.scaling.exercise.model.Product;
import com.scaling.exercise.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * GET /api/products
     * Light endpoint - just reads all products from DB.
     * Degrades when connection pool saturates.
     */
    @GetMapping
    public List<Product> getAllProducts() {
        return productService.getAllProducts();
    }

    /**
     * GET /api/products/{id}
     * Single row lookup - fastest endpoint.
     * Even this will slow down when the server is overwhelmed.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable Long id) {
        return productService.getProductById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/products/search?q=keyword
     * Intentionally slow - full table scan with LIKE query.
     * This is the "realistic bad query" that every codebase has.
     */
    @GetMapping("/search")
    public List<Product> searchProducts(@RequestParam("q") String query) {
        return productService.searchProducts(query);
    }

    /**
     * GET /api/products/category/{category}
     * Moderate cost - filters by category.
     */
    @GetMapping("/category/{category}")
    public List<Product> getByCategory(@PathVariable String category) {
        return productService.getByCategory(category);
    }

    /**
     * GET /api/products/stats/{category}
     * HEAVY endpoint - reads all products in a category,
     * runs expensive discount computation on each one.
     * This is the endpoint that breaks the server.
     */
    @GetMapping("/stats/{category}")
    public ProductService.ProductStats getCategoryStats(@PathVariable String category) {
        return productService.computeCategoryStats(category);
    }

    /**
     * POST /api/products
     * Write endpoint - creates contention on DB writes under load.
     */
    @PostMapping
    public Product createProduct(@RequestBody Product product) {
        return productService.createProduct(product);
    }

    /**
     * GET /api/health
     * Simple health check - useful to see when even this starts timing out.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "timestamp", System.currentTimeMillis(),
                "freeMemory", Runtime.getRuntime().freeMemory(),
                "totalMemory", Runtime.getRuntime().totalMemory(),
                "maxMemory", Runtime.getRuntime().maxMemory(),
                "availableProcessors", Runtime.getRuntime().availableProcessors()
        );
    }
}
