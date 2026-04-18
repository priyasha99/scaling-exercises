package com.scaling.exercise.service;

import com.scaling.exercise.model.Product;
import com.scaling.exercise.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Simple DB read - fast under low load, degrades under concurrency
     * as connection pool saturates and H2 locks contend.
     */
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public Optional<Product> getProductById(Long id) {
        return productRepository.findById(id);
    }

    /**
     * Search with a LIKE query - intentionally unoptimized.
     * Triggers full table scans, which is exactly what we want
     * to show DB pressure under load.
     */
    public List<Product> searchProducts(String keyword) {
        return productRepository.searchByNameOrDescription(keyword);
    }

    public List<Product> getByCategory(String category) {
        return productRepository.findByCategory(category);
    }

    /**
     * Simulates a "realistic" product price computation.
     * Adds CPU pressure: iterates all products, applies discount logic,
     * computes aggregates. Under high concurrency, this starves other
     * requests of CPU time.
     */
    public ProductStats computeCategoryStats(String category) {
        List<Product> products = productRepository.findByCategory(category);

        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal minPrice = null;
        BigDecimal maxPrice = null;
        int totalStock = 0;

        for (Product p : products) {
            // Simulate discount computation (CPU work)
            BigDecimal discount = computeDiscount(p);
            BigDecimal effectivePrice = p.getPrice().subtract(discount);

            totalValue = totalValue.add(effectivePrice.multiply(BigDecimal.valueOf(p.getStockQuantity())));
            totalStock += p.getStockQuantity();

            if (minPrice == null || effectivePrice.compareTo(minPrice) < 0) {
                minPrice = effectivePrice;
            }
            if (maxPrice == null || effectivePrice.compareTo(maxPrice) > 0) {
                maxPrice = effectivePrice;
            }
        }

        BigDecimal avgPrice = products.isEmpty() ? BigDecimal.ZERO :
                totalValue.divide(BigDecimal.valueOf(totalStock == 0 ? 1 : totalStock), 2, BigDecimal.ROUND_HALF_UP);

        return new ProductStats(category, products.size(), totalValue, avgPrice, minPrice, maxPrice, totalStock);
    }

    /**
     * Deliberately expensive discount computation.
     * Uses unnecessary iterations to simulate realistic business logic
     * that consumes CPU (think tax calculations, compliance checks, etc.)
     */
    private BigDecimal computeDiscount(Product product) {
        BigDecimal discount = BigDecimal.ZERO;

        // Simulate tiered discount logic with unnecessary computation
        for (int i = 0; i < 100; i++) {
            BigDecimal tier = product.getPrice()
                    .multiply(BigDecimal.valueOf(Math.sin(i * 0.01)))
                    .abs();
            if (tier.compareTo(BigDecimal.valueOf(5)) > 0) {
                discount = discount.add(BigDecimal.valueOf(0.01));
            }
        }

        if (product.getStockQuantity() > 100) {
            discount = discount.add(product.getPrice().multiply(BigDecimal.valueOf(0.05)));
        }

        return discount.min(product.getPrice().multiply(BigDecimal.valueOf(0.3)));
    }

    public Product createProduct(Product product) {
        return productRepository.save(product);
    }

    // Inner class for stats response
    public record ProductStats(
            String category,
            int productCount,
            BigDecimal totalInventoryValue,
            BigDecimal averagePrice,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            int totalStock
    ) {}
}
