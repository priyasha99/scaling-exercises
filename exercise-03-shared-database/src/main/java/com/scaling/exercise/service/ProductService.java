package com.scaling.exercise.service;

import com.scaling.exercise.model.Product;
import com.scaling.exercise.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Same business logic as Exercise 01/02.
 *
 * KEY CHANGE for Exercise 03 Part C (Read Replicas):
 * Read methods are annotated with @Transactional(readOnly = true).
 * When the "replicas" profile is active, our ReadWriteRoutingDataSource
 * checks this flag and routes read-only transactions to a replica
 * database, while writes go to the primary.
 *
 * Without these annotations, ALL queries would hit the primary —
 * defeating the purpose of read replicas.
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Read-only: routes to replica in Part C.
     * With a shared PostgreSQL, this query hits disk (not in-memory like H2).
     * 5000 products × multiple concurrent requests = real I/O pressure.
     */
    @Transactional(readOnly = true)
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Product> getProductById(Long id) {
        return productRepository.findById(id);
    }

    /**
     * Read-only: routes to replica in Part C.
     * The LIKE query is even more expensive on PostgreSQL than H2.
     * H2 ran entirely in-memory. PostgreSQL reads from disk (or
     * shared_buffers cache if lucky). Under concurrent load,
     * multiple sequential scans compete for I/O bandwidth.
     */
    @Transactional(readOnly = true)
    public List<Product> searchProducts(String keyword) {
        return productRepository.searchByNameOrDescription(keyword);
    }

    @Transactional(readOnly = true)
    public List<Product> getByCategory(String category) {
        return productRepository.findByCategory(category);
    }

    /**
     * Read-only (reads products, computes stats in Java).
     * Routes to replica in Part C.
     * CPU-heavy: iterates all products in category, runs discount
     * computation on each. Holds a DB connection for the duration
     * of the read, then burns CPU for the computation.
     */
    @Transactional(readOnly = true)
    public ProductStats computeCategoryStats(String category) {
        List<Product> products = productRepository.findByCategory(category);

        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal minPrice = null;
        BigDecimal maxPrice = null;
        int totalStock = 0;

        for (Product p : products) {
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
     * Same as Exercise 01 — 100 iterations of Math.sin.
     */
    private BigDecimal computeDiscount(Product product) {
        BigDecimal discount = BigDecimal.ZERO;

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

    /**
     * Write operation: always goes to primary.
     * Under concurrent load, INSERTs compete for table-level locks
     * (auto-increment sequence) and WAL writes.
     */
    @Transactional
    public Product createProduct(Product product) {
        return productRepository.save(product);
    }

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
