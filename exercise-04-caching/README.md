# Exercise 04: Caching with Redis

**Goal:** Add Redis caching on top of the read replica setup from Exercise 03. See how caching eliminates redundant database queries, reduces CPU usage, and dramatically improves throughput. Then explore the hard part: cache invalidation.

**What you'll learn:**
- How Redis works as a shared cache across multiple app servers
- How Spring's `@Cacheable` and `@CacheEvict` annotations work
- The difference between in-memory caching (per-server) and distributed caching (shared Redis)
- Why cache hit rate matters more than cache size
- Cache invalidation strategies and the write-heavy trade-off
- How caching changes the bottleneck map (database → app server → Redis)

**Prerequisites:** Complete Exercise 03 first. You need to understand shared databases and read replicas.

---

## The Problem We're Solving

In Exercise 03, we added read replicas to reduce database load. It helped — throughput went from 28 req/s to 40 req/s, errors dropped from 22% to 5.6%. But the improvement was limited by app server CPU (computeDiscount burns CPU on every request) and the replica still handled every read query.

The key insight: **most reads are redundant**. The same 8 categories get queried over and over. The same search terms repeat. Product data rarely changes. Why query the database (even a replica) for data that hasn't changed since the last request?

Caching stores the result of expensive operations and returns the stored result on subsequent calls. The database is never touched. The CPU-heavy computation never runs. The response comes from memory.

---

## Part A: Adding Redis (The Setup)

### Architecture

```
Client → [ Nginx ] → [ App 1 ] → [ Redis ] cache HIT → done!
                    → [ App 2 ]      ↓ cache MISS
                    → [ App 3 ] ──writes──→ [ PG Primary ]
                                ──reads───→ [ PG Replica ]  ← WAL
```

On a cache HIT:
1. Request arrives at an app server
2. Spring checks Redis for a cached result
3. Found → return immediately. No database connection. No SQL. No CPU-heavy computation.

On a cache MISS:
1. Request arrives at an app server
2. Spring checks Redis — not found
3. Read/write routing sends query to replica (or primary for writes)
4. Result comes back, Spring stores it in Redis with a TTL
5. Next request with the same parameters → cache HIT

### What Changed From Exercise 03

- **pom.xml:** Added `spring-boot-starter-data-redis` and `jackson-datatype-jsr310`
- **Product.java:** Added `implements Serializable` (required for Redis serialization)
- **RedisConfig.java:** NEW — configures cache manager with JSON serialization and per-cache TTLs
- **ProductService.java:** Added `@Cacheable` on read methods, `@CacheEvict` on write method
- **CacheMetrics.java:** NEW — tracks hit/miss counts per cache
- **CacheHitInterceptor.java:** NEW — adds `X-Cache-Status` header to responses
- **ProductController.java:** Added `/cache-stats` endpoint
- **docker-compose files:** Added Redis container

### Why Redis Instead of In-Memory Cache?

You could use Caffeine or Guava for in-memory caching — it's faster (no network hop). But with 3 app servers:

```
In-memory cache (Caffeine):
  App 1 caches "Electronics" stats
  App 2 doesn't have it → queries DB
  App 3 doesn't have it → queries DB
  = 3 cache misses for the same data

Distributed cache (Redis):
  App 1 caches "Electronics" stats in Redis
  App 2 checks Redis → HIT (same data)
  App 3 checks Redis → HIT (same data)
  = 1 miss + 2 hits
```

Redis is shared across all servers. One miss populates the cache for everyone. With 3 servers, this roughly triples your effective hit rate compared to per-server caching.

### Cache Configuration

Each cache has a different TTL based on how frequently the data changes:

| Cache | TTL | Why |
|---|---|---|
| `product` (by ID) | 10 min | Products rarely change |
| `products_by_category` | 5 min | Changes when products are added |
| `category_stats` | 5 min | Expensive to compute, same categories repeat |
| `search` | 3 min | New products might match search terms |
| `all_products` | 2 min | Changes with every write |

### Running Part A

#### Step 1: Start the cluster

```bash
cd exercise-04-caching

# Start with replicas + Redis
docker compose -f docker-compose.replicas.yml up --build
```

Wait for all containers to be healthy (~60 seconds).

#### Step 2: Verify Redis is connected

```bash
# Check app health (should show cacheHitRate)
curl -s http://localhost/api/products/health | python3 -m json.tool
```

#### Step 3: See caching in action

```bash
# First request — cache MISS (hits the database)
curl -si http://localhost/api/products/stats/Electronics | head -20

# Look for: X-Cache-Status: MISS

# Second request — cache HIT (from Redis, no DB query)
curl -si http://localhost/api/products/stats/Electronics | head -20

# Look for: X-Cache-Status: HIT
# Notice the response time difference!
```

#### Step 4: Inspect Redis

```bash
# Connect to Redis CLI
docker compose -f docker-compose.replicas.yml exec redis redis-cli

# See all cached keys
KEYS *

# Check a specific cached value (human-readable JSON)
GET "category_stats::Electronics"

# See memory usage
INFO memory

# Monitor cache operations in real time (Ctrl+C to stop)
MONITOR
```

#### Step 5: Check cache stats

```bash
curl -s http://localhost/api/products/cache-stats | python3 -m json.tool
```

This shows per-cache hit/miss rates for the server that handled the request.

---

## Part B: Cache Performance Under Load

### The Experiment

Run the same 500 VU load profile against the cached setup.

#### Step 1: Flush Redis (start with cold cache)

```bash
docker compose -f docker-compose.replicas.yml exec redis redis-cli FLUSHALL
```

#### Step 2: Run the load test

```bash
cd loadtest
K6_WEB_DASHBOARD=true k6 run 02-with-cache.js
```

#### Step 3: Watch the dashboard

Open http://localhost:5665 and watch:
- Response times drop as the cache warms up
- The first 10-20 seconds have higher latency (cold cache)
- After cache is warm, most requests complete in <50ms

#### Step 4: Monitor everything

```bash
# Terminal 2: Container resources
docker stats

# Terminal 3: Redis stats
docker compose -f docker-compose.replicas.yml exec redis redis-cli INFO stats | grep -E "hits|misses|keys"

# Terminal 4: Check app cache stats
curl -s http://localhost/api/products/cache-stats | python3 -m json.tool
```

### What to Observe

**Redis keyspace_hits vs keyspace_misses:**
```bash
docker compose -f docker-compose.replicas.yml exec redis redis-cli INFO stats | grep keyspace
```
A high hit ratio (>80%) means caching is working well.

**PostgreSQL CPU:**
Compare to Exercise 03 Test 4. With caching, the replica should be much less busy — most reads never reach it.

**App server CPU:**
Should be lower too. `computeCategoryStats()` is the most CPU-heavy method, and it's cached. On a cache HIT, the pre-computed `ProductStats` comes from Redis instantly — no `Math.sin()` loops.

### Reference Results — With Cache (500 VUs)

*Run the test and fill in your results here.*

| Metric | Ex03: Replicas, No Cache | Ex04: Replicas + Redis |
|---|---|---|
| Median | 6,450ms | *your result* |
| p95 | 15,000ms | *your result* |
| Errors | 5.60% | *your result* |
| Throughput | 40 req/s | *your result* |
| Cache hit rate | N/A | *your result* |
| Under 2s | 33% | *your result* |

---

## Part C: Cache Invalidation (The Hard Part)

> "There are only two hard things in Computer Science: cache invalidation and naming things."
> — Phil Karlton

### The Problem

When a new product is created:
1. The `all_products` cache is now stale (missing the new product)
2. The `products_by_category` cache for that category is stale
3. The `category_stats` for that category are wrong
4. Any `search` result that would match the new product is stale

Our `createProduct()` method handles this with `@CacheEvict`:

```java
@CacheEvict(value = {"all_products", "search"}, allEntries = true)
@Transactional
public Product createProduct(Product product) {
    // After this method completes, Spring evicts:
    //   - all entries in "all_products" cache
    //   - all entries in "search" cache
    // The next read will be a MISS → fresh data from DB
    return productRepository.save(product);
}
```

### The Trade-Off

More writes → more evictions → lower cache hit rate → more database load.

Test 2 uses 10% writes. Test 3 increases to 30% writes. Compare:

```bash
# Flush cache first
docker compose -f docker-compose.replicas.yml exec redis redis-cli FLUSHALL

# Run write-heavy test
cd loadtest
K6_WEB_DASHBOARD=true k6 run 03-cache-invalidation.js
```

### Reference Results — Write-Heavy (500 VUs, 30% Writes)

*Run the test and fill in your results here.*

| Metric | Test 2 (10% writes) | Test 3 (30% writes) |
|---|---|---|
| Median | *your result* | *your result* |
| Errors | *your result* | *your result* |
| Throughput | *your result* | *your result* |
| Cache hit rate | *your result* | *your result* |

### Cache Invalidation Strategies

Our approach (evict on write) is the simplest strategy. There are others:

**1. Write-through cache:** Write to both the database AND the cache simultaneously. No stale window, but writes are slower (two destinations).

**2. Write-behind cache:** Write to the cache first, then asynchronously write to the database. Fast writes, but risk data loss if Redis crashes before the DB write.

**3. TTL-only (no explicit invalidation):** Let cached data expire naturally. Simple, but users see stale data until the TTL expires. Works for data where slight staleness is acceptable (product recommendations, analytics dashboards).

**4. Event-driven invalidation:** The database publishes change events (PostgreSQL NOTIFY, Debezium CDC), and a listener evicts the corresponding cache entries. Most accurate, but most complex to set up.

Our approach is **evict on write** — simple, correct, but can over-evict (we clear ALL search results when any product is created, even though most search results are unaffected).

---

## The Full Comparison

| Config | Median | Throughput | Errors | Under 2s |
|---|---|---|---|---|
| Ex03: Single PG (500 VUs) | 10,576ms | 28 req/s | 22.49% | 11% |
| Ex03: Replicas (500 VUs) | 6,450ms | 40 req/s | 5.60% | 33% |
| Ex04: Replicas + Redis (500 VUs) | *your result* | *your result* | *your result* | *your result* |
| Ex04: Write-heavy 30% (500 VUs) | *your result* | *your result* | *your result* | *your result* |

---

## Cleanup

```bash
# Stop the replicas + Redis setup
docker compose -f docker-compose.replicas.yml down -v

# Or stop the single-PG + Redis setup
docker compose down -v
```

---

## Discussion Questions

1. **The cache hit rate depends on the number of unique keys.** We have 8 categories, 10 search terms, and 5,000 product IDs. Which cache has the highest hit rate? Which has the lowest? Why?

2. **All 3 app servers share one Redis.** What happens if Redis goes down? How would you handle it? (Hint: cache-aside pattern — treat cache as optional, fall back to database)

3. **Our TTLs are static (2-10 minutes).** What if a product's price changes and a user sees the old price from cache? How would you handle time-sensitive data differently from slowly-changing data?

4. **We evict ALL search results when ANY product is created.** This is safe but wasteful — creating a "Books" product invalidates the cached results for "search::Wireless". How could you make invalidation more targeted?

5. **Redis stores data in memory. Our Redis has a 200MB limit.** What happens when it's full? (Hint: `maxmemory-policy allkeys-lru` — Redis evicts the least recently used key to make room)

6. **In-memory caching (Caffeine) is ~100x faster per lookup than Redis** (nanoseconds vs microseconds). When would you choose Caffeine over Redis? When would you use both (L1/L2 cache)?

7. **The `computeCategoryStats()` cache saves the most CPU time.** What if you need real-time stats (always fresh)? Is there a middle ground between "always compute" and "cache for 5 minutes"?

---

## Bonus Challenges

- **Add Caffeine as an L1 cache** in front of Redis. Use Spring's `CompositeCacheManager` with a short Caffeine TTL (10 seconds) and longer Redis TTL (5 minutes). Measure the latency improvement.

- **Implement write-through caching:** Instead of `@CacheEvict`, use `@CachePut` on `createProduct()` to update the cache directly without evicting. Compare cache hit rates.

- **Add a cache warm-up endpoint** that pre-populates all category stats on app startup, so the first requests don't see cold-cache latency.

- **Monitor Redis with redis-cli MONITOR:** Run `MONITOR` during a load test and watch the cache operations in real time. Count GET vs SET operations.

---

## What's Next?

Caching reduced database load and improved response times, but we still have a hidden problem: session state. If a user logs in on Server 1, their session is stored in that server's memory. If the next request goes to Server 2, they're logged out.

Exercise 05 introduces **stateless sessions** — moving session data out of app server memory so users can hit any server without losing their session.
