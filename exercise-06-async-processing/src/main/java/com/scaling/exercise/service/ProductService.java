package com.scaling.exercise.service;

import com.scaling.exercise.config.CacheMetrics;
import com.scaling.exercise.model.Product;
import com.scaling.exercise.repository.ProductRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Product service with Redis caching.
 *
 * KEY CHANGES FROM EXERCISE 03:
 *
 * 1. @Cacheable annotations on read methods — results are stored in Redis
 *    after the first call. Subsequent calls with the same parameters skip
 *    the database entirely and return from Redis.
 *
 * 2. @CacheEvict annotations on write methods — when data changes, we
 *    invalidate the relevant caches so the next read fetches fresh data.
 *
 * 3. CacheMetrics tracking — we manually track hits/misses because
 *    @Cacheable is transparent (the method body only runs on a miss).
 *
 * HOW @Cacheable WORKS:
 *   - Spring intercepts the method call via AOP proxy
 *   - Checks Redis for a cached value matching the cache name + key
 *   - If found (HIT): returns the cached value, method body NEVER executes
 *   - If not found (MISS): executes the method, stores the result in Redis
 *
 * The read/write routing from Exercise 03 still applies:
 *   - @Transactional(readOnly = true) → replica (on cache MISS only)
 *   - @Transactional → primary (writes)
 *   - On cache HIT: no database connection is acquired at all
 *
 * This is the magic: a cache hit avoids BOTH the database query AND
 * the connection pool checkout. The response comes entirely from Redis.
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CacheManager cacheManager;

    /**
     * Thread-local to track cache hit/miss status for the current request.
     * [0] = "HIT" or "MISS", [1] = cache name (set on miss, used for metrics)
     */
    private static final ThreadLocal<String[]> cacheStatus = new ThreadLocal<>();

    public ProductService(ProductRepository productRepository, CacheManager cacheManager) {
        this.productRepository = productRepository;
        this.cacheManager = cacheManager;
    }

    /**
     * Called by CacheHitInterceptor BEFORE the controller method runs.
     * Sets the thread-local to "HIT" optimistically. If a @Cacheable
     * method body executes (miss), it will overwrite this to "MISS".
     * No metrics are recorded here — only in the actual hit/miss paths.
     */
    public static void prepareCacheTracking() {
        cacheStatus.set(new String[]{"HIT", null});
    }

    /**
     * Called by CacheHitInterceptor AFTER the controller method runs.
     * Returns the cache status and records the hit metric if applicable.
     */
    public static String getCacheStatus() {
        String[] status = cacheStatus.get();
        cacheStatus.remove(); // Clean up thread-local

        if (status == null) {
            return "NONE";
        }

        // If it's still "HIT" and we have a cache name from a miss that
        // was overwritten... no — if it's "HIT", the method body never ran,
        // so status[1] is still null. We record a generic "cache" hit.
        if ("HIT".equals(status[0]) && status[1] == null) {
            // This was a genuine cache hit but we don't know which cache.
            // The method body never executed, so we couldn't capture the name.
            CacheMetrics.recordHit("overall");
        }

        return status[0];
    }

    /**
     * Called inside @Cacheable method bodies to record a cache miss.
     * If this method is called, the @Cacheable method body is executing,
     * which means the cache didn't have the value.
     */
    private static void recordCacheMiss(String cacheName) {
        cacheStatus.set(new String[]{"MISS", cacheName});
        CacheMetrics.recordMiss(cacheName);
    }

    /**
     * Cache all products list.
     * Key: "all" (static — there's only one "all products" list)
     * TTL: 2 minutes (changes with every write)
     */
    @Cacheable(value = "all_products", key = "'all'")
    @Transactional(readOnly = true)
    public List<Product> getAllProducts() {
        // If we get here, it's a cache MISS — the method body only
        // executes when the result isn't in Redis
        recordCacheMiss("all_products");
        System.out.println("[Cache MISS] all_products");
        return productRepository.findAll();
    }

    /**
     * Cache individual product by ID.
     * Key: product ID (e.g., "product::42")
     * TTL: 10 minutes (products rarely change)
     *
     * NOTE: Returns Product (not Optional) because Optional doesn't
     * serialize/deserialize cleanly with Jackson + Redis type metadata.
     * Returns null if not found — Spring's @Cacheable handles null
     * gracefully (we configured disableCachingNullValues in RedisConfig).
     */
    @Cacheable(value = "product", key = "#id")
    @Transactional(readOnly = true)
    public Product getProductById(Long id) {
        recordCacheMiss("product");
        System.out.println("[Cache MISS] product::" + id);
        return productRepository.findById(id).orElse(null);
    }

    /**
     * Cache search results by keyword.
     * Key: search keyword (e.g., "search::Premium")
     * TTL: 3 minutes (new products might match)
     *
     * This is a great cache candidate — the LIKE query is expensive
     * (full table scan) and the same search terms get repeated often.
     */
    @Cacheable(value = "search", key = "#keyword")
    @Transactional(readOnly = true)
    public List<Product> searchProducts(String keyword) {
        recordCacheMiss("search");
        System.out.println("[Cache MISS] search::" + keyword);
        return productRepository.searchByNameOrDescription(keyword);
    }

    /**
     * Cache products by category.
     * Key: category name (e.g., "products_by_category::Electronics")
     * TTL: 5 minutes
     */
    @Cacheable(value = "products_by_category", key = "#category")
    @Transactional(readOnly = true)
    public List<Product> getByCategory(String category) {
        recordCacheMiss("products_by_category");
        System.out.println("[Cache MISS] products_by_category::" + category);
        return productRepository.findByCategory(category);
    }

    /**
     * Cache category stats.
     * Key: category name (e.g., "category_stats::Electronics")
     * TTL: 5 minutes
     *
     * This is the BEST cache candidate:
     *   - Expensive to compute (100 iterations of Math.sin per product)
     *   - Same categories queried repeatedly (only 8 categories)
     *   - Result doesn't change unless products are added/modified
     *
     * Without cache: each call queries DB + burns CPU computing discounts
     * With cache: instant response from Redis, zero DB load, zero CPU
     */
    @Cacheable(value = "category_stats", key = "#category")
    @Transactional(readOnly = true)
    public ProductStats computeCategoryStats(String category) {
        recordCacheMiss("category_stats");
        System.out.println("[Cache MISS] category_stats::" + category);

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
     * Same as Exercise 01/02/03 — 100 iterations of Math.sin.
     *
     * With caching, this only runs on cache MISS. On HIT, the
     * pre-computed ProductStats is returned from Redis instantly.
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
     * Write operation: creates a product AND invalidates related caches.
     *
     * CACHE INVALIDATION STRATEGY:
     * When a new product is created, we must invalidate:
     *   - all_products: the full list now has a new item
     *   - products_by_category: the new product's category listing changed
     *   - category_stats: stats for that category are now different
     *   - search: any search might now include this product
     *
     * We use @CacheEvict to clear entire caches. For search, we clear
     * ALL cached search results (allEntries=true) because we can't know
     * which search terms would match the new product.
     *
     * This is the fundamental cache invalidation trade-off:
     *   - Evict too little → stale data (users don't see new products)
     *   - Evict too much → poor hit rate (cache is always cold)
     */
    @CacheEvict(value = {"all_products", "search"}, allEntries = true)
    @Transactional
    public Product createProduct(Product product) {
        Product saved = productRepository.save(product);

        // Programmatically evict category-specific caches.
        // @CacheEvict can't use a dynamic key across multiple cache names,
        // so we use the CacheManager directly.
        String category = product.getCategory();
        evictFromCache("products_by_category", category);
        evictFromCache("category_stats", category);

        System.out.println("[Cache EVICT] all_products, search (all entries)");
        System.out.println("[Cache EVICT] products_by_category::" + category);
        System.out.println("[Cache EVICT] category_stats::" + category);

        return saved;
    }

    private void evictFromCache(String cacheName, String key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }

    /**
     * POJO instead of record for Redis serialization compatibility.
     *
     * Java records don't have a no-arg constructor, which Jackson needs
     * when deserializing with Redis type metadata (@class property).
     * Jackson can serialize a record fine, but when reading it back
     * from Redis, it fails because it can't construct the object.
     *
     * A regular class with a no-arg constructor + getters/setters works
     * with any serialization strategy.
     */
    public static class ProductStats {
        private String category;
        private int productCount;
        private BigDecimal totalInventoryValue;
        private BigDecimal averagePrice;
        private BigDecimal minPrice;
        private BigDecimal maxPrice;
        private int totalStock;

        public ProductStats() {} // Required for Jackson deserialization

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
