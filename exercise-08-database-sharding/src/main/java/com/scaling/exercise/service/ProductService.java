package com.scaling.exercise.service;

import com.scaling.exercise.config.CacheMetrics;
import com.scaling.exercise.model.Product;
import com.scaling.exercise.repository.ProductRepository;
import com.scaling.exercise.sharding.ShardContext;
import com.scaling.exercise.sharding.ShardMetrics;
import com.scaling.exercise.sharding.ShardingService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Product service — now shard-aware (Exercise 08).
 *
 * SHARDING STRATEGY:
 * Shard key = category. Hash-based routing: category.hashCode() % 2.
 *
 * QUERY TYPES:
 *   1. SINGLE-SHARD: category queries route to the one correct shard
 *      → getByCategory(), computeCategoryStats()
 *
 *   2. CROSS-SHARD (scatter-gather): queries without a category must
 *      fan out to ALL shards and merge results
 *      → getAllProducts(), searchProducts(), getProductById()
 *
 *   3. WRITES: route to the correct shard based on the product's category
 *      → createProduct()
 *
 * WHY CROSS-SHARD QUERIES ARE EXPENSIVE:
 * A single-shard query hits 1 database. A cross-shard query hits ALL
 * shards sequentially, then merges results in the app layer. With 2
 * shards, that's 2x the database round trips. With 10 shards, 10x.
 * This is the fundamental trade-off of sharding.
 *
 * CACHING + SHARDING:
 * Redis caching (from Exercise 04) is even more valuable with sharding.
 * A cache hit avoids the cross-shard fan-out entirely — the result
 * comes from Redis regardless of how many shards exist.
 *
 * TRANSACTION BOUNDARIES:
 * - Single-shard methods keep @Transactional (one shard, one connection)
 * - Cross-shard methods remove @Transactional (each shard query gets
 *   its own auto-commit transaction via Spring Data's defaults)
 * - A single @Transactional cannot span two DataSources
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CacheManager cacheManager;
    private final ShardingService shardingService;

    private static final ThreadLocal<String[]> cacheStatus = new ThreadLocal<>();

    public ProductService(ProductRepository productRepository,
                          CacheManager cacheManager,
                          ShardingService shardingService) {
        this.productRepository = productRepository;
        this.cacheManager = cacheManager;
        this.shardingService = shardingService;
    }

    public static void prepareCacheTracking() {
        cacheStatus.set(new String[]{"HIT", null});
    }

    public static String getCacheStatus() {
        String[] status = cacheStatus.get();
        cacheStatus.remove();
        if (status == null) return "NONE";
        if ("HIT".equals(status[0]) && status[1] == null) {
            CacheMetrics.recordHit("overall");
        }
        return status[0];
    }

    private static void recordCacheMiss(String cacheName) {
        cacheStatus.set(new String[]{"MISS", cacheName});
        CacheMetrics.recordMiss(cacheName);
    }

    // -------------------------------------------------------
    // CROSS-SHARD QUERIES (scatter-gather)
    // -------------------------------------------------------
    // These fan out to all shards and merge results.
    // No @Transactional — each shard query runs in its own transaction.
    // -------------------------------------------------------

    /**
     * Get all products from ALL shards.
     *
     * CROSS-SHARD: queries each shard and merges results.
     * With 2 shards, this makes 2 database round trips.
     * With caching, the merged result is stored in Redis — subsequent
     * calls skip the fan-out entirely.
     */
    @Cacheable(value = "all_products", key = "'all'")
    public List<Product> getAllProducts() {
        recordCacheMiss("all_products");
        System.out.println("[Cache MISS] all_products");

        if (!shardingService.isEnabled()) {
            return productRepository.findAll();
        }

        // Fan out to all shards
        List<Product> all = new ArrayList<>();
        for (int i = 0; i < shardingService.getShardCount(); i++) {
            ShardContext.setCurrentShard(shardingService.getShardId(i));
            try {
                List<Product> shardResults = productRepository.findAll();
                all.addAll(shardResults);
                System.out.println("[Shard Fan-out] shard-" + i + ": " + shardResults.size() + " products");
            } finally {
                ShardContext.clear();
            }
        }
        ShardMetrics.recordCrossShardQuery();
        return all;
    }

    /**
     * Get product by ID — must check all shards.
     *
     * CROSS-SHARD: we don't know which shard has this ID because
     * the shard key is category, not ID. We try each shard until
     * we find it. This is a fundamental limitation: lookups by
     * non-shard-key require scatter-gather.
     *
     * NOTE: With auto-increment IDs, both shards start from 1.
     * ID 42 might exist on BOTH shards (different products).
     * In production, you'd use UUIDs or shard-aware ID generation
     * (e.g., Snowflake IDs) to avoid this collision.
     */
    @Cacheable(value = "product", key = "#id")
    public Product getProductById(Long id) {
        recordCacheMiss("product");
        System.out.println("[Cache MISS] product::" + id);

        if (!shardingService.isEnabled()) {
            return productRepository.findById(id).orElse(null);
        }

        // Try each shard until found
        for (int i = 0; i < shardingService.getShardCount(); i++) {
            ShardContext.setCurrentShard(shardingService.getShardId(i));
            try {
                Product p = productRepository.findById(id).orElse(null);
                if (p != null) return p;
            } finally {
                ShardContext.clear();
            }
        }
        ShardMetrics.recordCrossShardQuery();
        return null;
    }

    /**
     * Search across ALL shards.
     *
     * CROSS-SHARD: the search keyword could match products in any category,
     * so we must query every shard. This is the worst case for sharding —
     * a full-text search that ignores the shard key.
     *
     * In production, you'd use a search index (Elasticsearch) that has
     * a complete view of all products. The search index is the fan-out
     * layer — the shards only handle writes and primary key lookups.
     */
    @Cacheable(value = "search", key = "#keyword")
    public List<Product> searchProducts(String keyword) {
        recordCacheMiss("search");
        System.out.println("[Cache MISS] search::" + keyword);

        if (!shardingService.isEnabled()) {
            return productRepository.searchByNameOrDescription(keyword);
        }

        // Fan out to all shards
        List<Product> all = new ArrayList<>();
        for (int i = 0; i < shardingService.getShardCount(); i++) {
            ShardContext.setCurrentShard(shardingService.getShardId(i));
            try {
                all.addAll(productRepository.searchByNameOrDescription(keyword));
            } finally {
                ShardContext.clear();
            }
        }
        ShardMetrics.recordCrossShardQuery();
        return all;
    }

    // -------------------------------------------------------
    // SINGLE-SHARD QUERIES (shard key known)
    // -------------------------------------------------------
    // These route to exactly one shard. Fast — no fan-out.
    // @Transactional works because it's one datasource.
    // -------------------------------------------------------

    /**
     * Get products by category — routes to the ONE correct shard.
     *
     * SINGLE-SHARD: we know the category, so we know the shard.
     * This is why category is a good shard key — the most common
     * queries (browse by category, category stats) are single-shard.
     */
    @Cacheable(value = "products_by_category", key = "#category")
    @Transactional(readOnly = true)
    public List<Product> getByCategory(String category) {
        recordCacheMiss("products_by_category");
        System.out.println("[Cache MISS] products_by_category::" + category);

        if (shardingService.isEnabled()) {
            ShardContext.setCurrentShard(shardingService.getShardIdForCategory(category));
        }
        try {
            return productRepository.findByCategory(category);
        } finally {
            ShardContext.clear();
        }
    }

    /**
     * Compute category stats — routes to ONE shard.
     *
     * SINGLE-SHARD: all products in a category live on the same shard.
     * The expensive computation (discount calculation) runs against
     * only the products on that shard — not the full dataset.
     */
    @Cacheable(value = "category_stats", key = "#category")
    @Transactional(readOnly = true)
    public ProductStats computeCategoryStats(String category) {
        recordCacheMiss("category_stats");
        System.out.println("[Cache MISS] category_stats::" + category);

        if (shardingService.isEnabled()) {
            ShardContext.setCurrentShard(shardingService.getShardIdForCategory(category));
        }
        try {
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
        } finally {
            ShardContext.clear();
        }
    }

    // -------------------------------------------------------
    // WRITE OPERATIONS (route to correct shard)
    // -------------------------------------------------------

    /**
     * Create a product on the correct shard based on its category.
     *
     * The shard is determined by hash(category) % shardCount.
     * This ensures all products in the same category are co-located.
     */
    @CacheEvict(value = {"all_products", "search"}, allEntries = true)
    @Transactional
    public Product createProduct(Product product) {
        if (shardingService.isEnabled()) {
            ShardContext.setCurrentShard(
                    shardingService.getShardIdForCategory(product.getCategory()));
        }
        try {
            Product saved = productRepository.save(product);

            String category = product.getCategory();
            evictFromCache("products_by_category", category);
            evictFromCache("category_stats", category);

            System.out.println("[Cache EVICT] all_products, search (all entries)");
            System.out.println("[Cache EVICT] products_by_category::" + category);
            System.out.println("[Cache EVICT] category_stats::" + category);

            return saved;
        } finally {
            ShardContext.clear();
        }
    }

    // -------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------

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

    private void evictFromCache(String cacheName, String key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }

    public static class ProductStats {
        private String category;
        private int productCount;
        private BigDecimal totalInventoryValue;
        private BigDecimal averagePrice;
        private BigDecimal minPrice;
        private BigDecimal maxPrice;
        private int totalStock;

        public ProductStats() {}

        public ProductStats(String category, int productCount, BigDecimal totalInventoryValue,
                            BigDecimal averagePrice, BigDecimal minPrice, BigDecimal maxPrice, int totalStock) {
            this.category = category;
            this.productCount = productCount;
            this.totalInventoryValue = totalInventoryValue;
            this.averagePrice = averagePrice;
            this.minPrice = minPrice;
            this.maxPrice = maxPrice;
            this.totalStock = totalStock;
        }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public int getProductCount() { return productCount; }
        public void setProductCount(int productCount) { this.productCount = productCount; }
        public BigDecimal getTotalInventoryValue() { return totalInventoryValue; }
        public void setTotalInventoryValue(BigDecimal totalInventoryValue) { this.totalInventoryValue = totalInventoryValue; }
        public BigDecimal getAveragePrice() { return averagePrice; }
        public void setAveragePrice(BigDecimal averagePrice) { this.averagePrice = averagePrice; }
        public BigDecimal getMinPrice() { return minPrice; }
        public void setMinPrice(BigDecimal minPrice) { this.minPrice = minPrice; }
        public BigDecimal getMaxPrice() { return maxPrice; }
        public void setMaxPrice(BigDecimal maxPrice) { this.maxPrice = maxPrice; }
        public int getTotalStock() { return totalStock; }
        public void setTotalStock(int totalStock) { this.totalStock = totalStock; }
    }
}
