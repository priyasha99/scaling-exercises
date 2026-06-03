# Exercise 03: Shared Database — The Next Bottleneck

**Goal:** Discover what happens when you fix Exercise 02's data inconsistency by adding a shared database. Spoiler: the database becomes the new bottleneck. Then see how read replicas help distribute the load.

**What you'll learn:**
- Shared PostgreSQL solves data inconsistency but introduces a new bottleneck
- Adding more app servers doesn't help when the database is the ceiling
- How to monitor PostgreSQL to identify it as the bottleneck
- How PostgreSQL streaming replication works
- How Spring Boot read/write routing splits traffic between primary and replicas
- The trade-off between consistency and read performance (replication lag)
- Several Spring Boot gotchas that can silently break read/write routing

**Prerequisites:** Complete Exercise 02 first. You need to understand horizontal scaling and load balancing.

**Important — consistent results across machines:** As with previous exercises, absolute numbers vary by hardware. The *patterns* (DB bottleneck, scaling ceiling, read replica improvement) will be the same on any machine.

---

## The Problem We're Solving

In Exercise 02, three app servers behind Nginx handled 500 users easily. But each server had its own H2 in-memory database:

```
Client → [ Nginx ] → [ App 1 ] → [ H2 DB 1 ]  ← Different data!
                    → [ App 2 ] → [ H2 DB 2 ]  ← Different data!
                    → [ App 3 ] → [ H2 DB 3 ]  ← Different data!
```

A product created on Server 1 didn't exist on Server 2. If a user created a product and immediately refreshed the page, the product might "disappear" because the second request went to a different server.

The fix is obvious: one shared database. But that fix creates the next problem.

---

## Part A: Shared PostgreSQL (The Fix)

### Architecture

```
Client → [ Nginx ] → [ App 1 ] ──\
                    → [ App 2 ] ───+→ [ PostgreSQL ]
                    → [ App 3 ] ──/
```

Same app servers as Exercise 02, same Nginx load balancer. But now all three connect to a single PostgreSQL instance. Create a product on any server, read it from any other server — consistent data.

### What Changed From Exercise 02

The app code is nearly identical. The differences:
- **pom.xml:** PostgreSQL driver instead of H2
- **application.properties:** `jdbc:postgresql://postgres:5432/productdb` instead of `jdbc:h2:mem:productdb`
- **ProductService:** Added `@Transactional(readOnly = true)` to read methods (needed for Part C)
- **ProductController:** Added `X-Server-Id` response header so you can see which server handled each request
- **DataSeeder:** Same seed logic, but now there's a race condition (see below)

JPA handles the rest — same entity, same repository, same queries.

### The DataSeeder Race Condition

With Exercise 01/02, each server had its own H2 database, so each seeded independently. Now all 3 servers share one PostgreSQL. When they start simultaneously, all three check `productRepository.count()`, all see 0, and all try to seed 5,000 products — resulting in 15,000 products instead of 5,000.

This is harmless for the exercise (more data = heavier queries = more visible bottleneck), but it's a real-world problem. In production, you'd solve this with database migrations (Flyway/Liquibase), a dedicated initialization job, or PostgreSQL advisory locks (`SELECT pg_advisory_lock(1)`).

### Running Part A

#### Step 1: Start the cluster

```bash
cd exercise-03-shared-database

docker compose up --build
```

Wait for all containers to report healthy. PostgreSQL starts first (~10s), then app servers boot (~30-40s each for JVM + DB migration + data seeding).

**Note:** pgAdmin is included in the setup. Access it at http://localhost:5050 (login: `admin@admin.com` / `admin`). Add a server connection: host=`postgres`, port=`5432`, user=`app`, password=`app_password`.

#### Step 2: Verify shared data

```bash
# Create a product (goes to any server)
curl -X POST http://localhost/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Shared DB Test","description":"This should be visible everywhere","price":42.00,"category":"Electronics","stockQuantity":100}'

# Read it back multiple times — different servers, same data
# (Note: product ID will be 15001 if all 3 servers seeded)
for i in {1..6}; do curl -s http://localhost/api/products/health | python3 -c "import sys,json; print(json.load(sys.stdin)['serverId'])"; done
```

You'll see 2-3 different server IDs. Each request goes to a different server, but the product data is always consistent — because they all share one database.

#### Step 3: Run baseline test

```bash
cd loadtest
K6_WEB_DASHBOARD=true k6 run 01-shared-db-baseline.js
```

#### Step 4: Monitor

```bash
# In a separate terminal
docker stats

# Check PostgreSQL connections
docker exec exercise-03-shared-database-postgres-1 \
  psql -U app -d productdb -c "SELECT count(*) FROM pg_stat_activity WHERE state = 'active';"
```

### Reference Results — Baseline (50 VUs)

| Metric | Ex02: H2 in-memory (50 VUs) | Ex03: PostgreSQL (50 VUs) |
|---|---|---|
| Median | ~50ms | 260ms |
| p95 | ~500ms | 3,480ms |
| Throughput | ~80 req/s | 29 req/s |
| Errors | ~0% | 0.40% |
| Data consistent | N/A (separate DBs) | **100%** (326/326) |

Even at 50 VUs, PostgreSQL is noticeably slower — 5x higher median, 3x lower throughput. That's the cost of moving from in-memory to disk-based storage over the network. But data consistency is perfect: every product created on one server was readable from another.

---

## Part B: The Database Becomes the Bottleneck

### The Experiment

Run the same load profile that worked fine in Exercise 02 (500 VUs). Watch how performance differs now that all requests funnel through one PostgreSQL.

```bash
cd loadtest
K6_WEB_DASHBOARD=true k6 run 02-db-bottleneck.js
```

### What to Observe

While the test runs, open a second terminal and watch:

```bash
# Container resource usage
docker stats

# Active PostgreSQL connections and queries
docker exec exercise-03-shared-database-postgres-1 \
  psql -U app -d productdb -c "
    SELECT state, count(*)
    FROM pg_stat_activity
    WHERE datname = 'productdb'
    GROUP BY state;"

# Long-running queries
docker exec exercise-03-shared-database-postgres-1 \
  psql -U app -d productdb -c "
    SELECT pid, now() - pg_stat_activity.query_start AS duration, query
    FROM pg_stat_activity
    WHERE state = 'active' AND datname = 'productdb'
    ORDER BY duration DESC
    LIMIT 5;"
```

### Reference Results — DB Bottleneck (500 VUs)

| Metric | Ex02: 3 servers + H2 | Ex03: 3 servers + PostgreSQL |
|---|---|---|
| Median | 248ms | **10,576ms** (42x slower) |
| p95 | 5,345ms | **15,000ms** (timeout ceiling) |
| Errors | 0.03% | **22.49%** (750x more) |
| Throughput | 166.7 req/s | **28 req/s** (6x lower) |
| Timeouts | 3 | **2,232** |
| Under 2s | 72% | **11%** |

The shared database completely collapsed under the same load Exercise 02 handled easily. The throughput (28 req/s) is actually *lower* than Exercise 01's single server with H2 (36.6 req/s). The shared database isn't just a bottleneck — it's worse than having no shared database at all under heavy load.

### Test 3: Prove Adding Servers Doesn't Help

This is the key insight. Start this test, then scale app servers during it:

```bash
# Terminal 1: Start the 3-minute load test
cd loadtest
K6_WEB_DASHBOARD=true k6 run 03-more-servers-dont-help.js

# Terminal 2: Scale from 3 to 6 servers while the test runs
docker compose up --scale app=6 --no-recreate -d && \
sleep 35 && \
docker compose exec nginx nginx -s reload
```

**Watch the k6 dashboard (http://localhost:5665):**
- Does throughput increase when you go from 3 to 6 servers?
- In Exercise 02, scaling from 1 to 3 gave 4.6x throughput
- Here, scaling from 3 to 6 gives... barely any improvement

### Reference Results — 6 Servers (300 VUs)

| Metric | Test 2 (3 servers, 500 VUs) | Test 3 (6 servers, 300 VUs) |
|---|---|---|
| Median | 10,576ms | **15,000ms** (worse!) |
| Throughput | 28 req/s | **20 req/s** (worse!) |
| Errors | 22.49% | 12.84% |
| Timeouts | 2,232 | **3,677** (more!) |
| Under 2s | 11% | **2%** |

Adding 3 more servers made things **worse**. More app servers = more connections competing for an already saturated database. It's like adding more cars to a traffic jam — more participants doesn't increase the road's capacity.

#### The Connection Pool Saturation

With 6 servers scaled up, we tried to check PostgreSQL's connection usage:

```bash
docker exec exercise-03-shared-database-postgres-1 \
  psql -U app -d productdb -c "SELECT max_conn, used, ..."
```

Result: `FATAL: sorry, too many clients already`

Even the admin tool couldn't connect! 6 servers × 10 HikariCP connections = 60, but `max_connections=50`. All 50 slots were consumed by app servers, leaving zero for monitoring. This is why production systems always reserve connections for admin access (`superuser_reserved_connections` in PostgreSQL) and use connection poolers like PgBouncer.

**After the test ends**, HikariCP keeps connections open (that's the point of a pool). Each server maintains `minimum-idle=5` connections. 6 servers × 5 = 30 idle connections, plus ~6 PostgreSQL internal processes = ~36 connections still held.

**Why it doesn't help — the restaurant analogy:**
- 3 servers × 10 connections = 30 connections to PostgreSQL
- 6 servers × 10 connections = 60 connections — exceeds `max_connections=50`!
- Even without hitting the connection limit, all 6 servers are waiting for the same PostgreSQL to process queries
- Adding more waiters doesn't make the kitchen cook faster

### When done, stop everything:

```bash
docker compose down -v
```

The `-v` flag removes the PostgreSQL data volume so you start fresh for Part C.

---

## Part C: Read Replicas (The Solution)

Most real-world applications are read-heavy: 80-90% of queries are reads (list products, search, view details), and only 10-20% are writes (create, update, delete). If we can offload reads to separate database copies, the primary database only handles writes and has much less contention.

### Architecture

```
Client → [ Nginx ] → [ App 1 ] ──writes──→ [ PG Primary ]
                    → [ App 2 ]                    │
                    → [ App 3 ] ──reads───→ [ PG Replica 1 ]  ← streams WAL
                                ──reads───→ [ PG Replica 2 ]  ← streams WAL
```

### How PostgreSQL Streaming Replication Works

1. **Primary** processes all writes and generates WAL (Write-Ahead Log) records
2. **Replicas** connect to the primary and continuously stream these WAL records
3. Replicas apply the WAL records to their own copy of the data
4. Replicas are read-only — any write attempt returns an error
5. There's a small **replication lag** (usually milliseconds) where a replica hasn't yet applied the latest write

The setup uses `pg_basebackup` to create the initial clone, then PostgreSQL handles the rest automatically.

### How Spring Read/Write Routing Works

The app uses a custom `ReadWriteRoutingDataSource` that checks if the current transaction is marked read-only:

```java
// In ProductService:
@Transactional(readOnly = true)   // → routes to replica
public List<Product> getAllProducts() { ... }

@Transactional                     // → routes to primary
public Product createProduct(Product p) { ... }
```

Under the hood:
1. `@Transactional(readOnly = true)` sets a thread-local flag
2. `ReadWriteRoutingDataSource` checks this flag
3. Returns the replica `DataSource` for reads, primary for writes
4. `LazyConnectionDataSourceProxy` ensures the connection isn't acquired until after the flag is set

#### Three Gotchas We Hit (and Fixed)

**Gotcha 1: `DataSource router not initialized`**

`AbstractRoutingDataSource` requires `afterPropertiesSet()` to be called after setting target DataSources. Without it, Hibernate crashes on startup with "DataSource router not initialized." The fix: call `routingDs.afterPropertiesSet()` explicitly in `DataSourceConfig`.

**Gotcha 2: Spring Boot ignores your custom DataSource**

If `spring.datasource.url` is configured, Spring Boot auto-creates a DataSource and Hibernate uses *that* instead of your custom routing one — even if yours is marked `@Primary`. The fix: add `spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` to `application-replicas.properties` so Spring Boot doesn't create a competing DataSource.

We confirmed this was the problem by adding debug logging to `ReadWriteRoutingDataSource.determineCurrentLookupKey()`. Before the fix: zero log output (our bean was never called). After: proper `PRIMARY`/`REPLICA` routing messages.

**Gotcha 3: `open-in-view` bypasses routing**

Spring Boot's `spring.jpa.open-in-view=true` (the default) opens an EntityManager at the start of each HTTP request — before `@Transactional` sets the `readOnly` flag. This can cause the routing DataSource to check the flag too early, always seeing `readOnly=false`, and routing everything to the primary. The fix: `spring.jpa.open-in-view=false` in the replicas profile.

### Running Part C

#### Step 1: Start the replicas setup

```bash
cd exercise-03-shared-database

# Clean up any previous data volumes
docker compose down -v 2>/dev/null
docker compose -f docker-compose.replicas.yml down -v 2>/dev/null

# Start the full setup
docker compose -f docker-compose.replicas.yml up --build
```

This takes longer to start because:
1. Primary PostgreSQL starts and initializes the replication user
2. Replicas clone the primary using `pg_basebackup` (~10-20s)
3. App servers boot and connect to both primary and replica

#### Step 2: Verify replication is working

```bash
# Check replication status on primary
docker exec exercise-03-shared-database-postgres-primary-1 \
  psql -U app -d productdb -c "SELECT * FROM pg_stat_replication;"

# Should show 2 replica connections with state = 'streaming'
```

#### Step 3: Verify read/write routing

```bash
# Hit a few read endpoints (should route to replica)
curl -s http://localhost/api/products/1 > /dev/null
curl -s http://localhost/api/products/category/Books > /dev/null

# Check app logs for routing messages
docker compose -f docker-compose.replicas.yml logs app | grep "DataSource Routing"
```

You should see both `→ PRIMARY` (Hibernate startup queries) and `→ REPLICA` (your read requests) in the output.

#### Step 4: Run the benchmark

```bash
cd loadtest
K6_WEB_DASHBOARD=true k6 run 04-read-replicas.js
```

While it runs, watch the CPU distribution:

```bash
docker stats
```

#### Step 5: Monitor replication lag

```bash
docker exec exercise-03-shared-database-postgres-primary-1 \
  psql -U app -d productdb -c "
    SELECT client_addr,
           state,
           sent_lsn,
           write_lsn,
           flush_lsn,
           replay_lsn,
           pg_wal_lsn_diff(sent_lsn, replay_lsn) AS replay_lag_bytes
    FROM pg_stat_replication;"
```

### Reference Results — Read Replicas (500 VUs)

| Metric | Test 2: Single PostgreSQL | Test 4: Primary + 2 Replicas | Improvement |
|---|---|---|---|
| Median | 10,576ms | 6,450ms | **1.6x faster** |
| p95 | 15,000ms | 15,000ms | Same (timeout ceiling) |
| Errors | 22.49% | 5.60% | **4x fewer** |
| Throughput | 28 req/s | 40 req/s | **1.4x higher** |
| Timeouts | 2,232 | 1,973 | 12% fewer |
| Under 2s | 11% | 33% | **3x more** |
| Under 5s | — | 43% | — |

### What the CPU Numbers Tell You

During the read replica test, `docker stats` showed:

| Container | CPU % | What's happening |
|---|---|---|
| postgres-primary | **1.17%** | Almost idle — only handles writes (~10% of traffic) + WAL streaming |
| postgres-replica-1 | **55.89%** | Handles all the reads — the new bottleneck |
| postgres-replica-2 | **5-8%** | Gets some read traffic but much less than replica-1 |
| app-1 | **107.63%** | CPU-pinned — `computeDiscount()` is the bottleneck here |
| app-3 | **104.73%** | CPU-pinned — same app-level bottleneck |
| app-2 | **65.77%** | Less loaded (round-robin isn't perfectly even) |

The read/write routing worked perfectly. The primary dropped from ~100% CPU (Test 2) to 1.17% — it's basically idle, just handling the 10% writes and streaming WAL to replicas.

### Why the Improvement Is Modest (40%, not 3x)

The improvement is real but not dramatic because there are **two remaining bottlenecks**:

1. **App server CPU is pinned.** `computeDiscount()` runs 100 iterations of `Math.sin()` per product, per request. With 15,000 products (triple-seeded), the stats endpoint processes ~1,875 products per category. Two app servers hit 104-107% CPU — they're the ceiling now, not the database.

2. **Only one replica handles most reads.** Our routing sends reads to `postgres-replica-1`. Replica-2 gets WAL stream traffic but limited query traffic. To fully use both replicas, you'd need a connection pooler (PgBouncer) or modify the routing to round-robin across replicas.

In production with proper hardware (not a laptop running 8+ containers on 4 cores), the improvement from read replicas is typically 2-3x for read-heavy workloads.

---

## The Full Comparison

| Config | Median | Throughput | Errors | Timeouts | Under 2s |
|---|---|---|---|---|---|
| Ex02: 3 servers + H2 (500 VUs) | 248ms | 166.7 req/s | 0.03% | 3 | 72% |
| Ex03 Baseline: 3 servers + PG (50 VUs) | 260ms | 29 req/s | 0.40% | 1 | 86% |
| Ex03 Test 2: 3 servers + PG (500 VUs) | 10,576ms | 28 req/s | 22.49% | 2,232 | 11% |
| Ex03 Test 3: 6 servers + PG (300 VUs) | 15,000ms | 20 req/s | 12.84% | 3,677 | 2% |
| Ex03 Test 4: 3 servers + replicas (500 VUs) | 6,450ms | 40 req/s | 5.60% | 1,973 | 33% |

The story in one table: H2 in-memory was fast but inconsistent. Shared PostgreSQL fixed consistency but became the bottleneck. Adding app servers made it worse. Read replicas helped — 4x fewer errors, 40% more throughput — but the improvement is limited by app server CPU and single-replica routing.

---

## Cleanup

```bash
# Stop the single-DB setup
docker compose down -v

# Stop the replicas setup
docker compose -f docker-compose.replicas.yml down -v
```

---

## Discussion Questions

1. **In Exercise 02, scaling from 1 to 3 servers gave 4.6x throughput. In Exercise 03, scaling from 3 to 6 servers made things worse.** Why? What changed between the two exercises that made adding servers effective in one and counterproductive in the other?

2. **PostgreSQL's `max_connections` is set to 50. With 6 servers × 10 HikariCP connections = 60.** We couldn't even run `psql` to check — "FATAL: sorry, too many clients already." How would you handle this in production? (Hint: PgBouncer — a connection pooler that sits between your app and PostgreSQL)

3. **The LIKE search query was fast on H2 but slow on PostgreSQL.** Why? H2 ran in the JVM's heap memory. PostgreSQL reads from disk (or shared_buffers if cached). What database feature would speed up text search? (Hint: full-text search indexes, or `pg_trgm` extension for LIKE optimization)

4. **Read replicas have replication lag.** A user creates a product, then immediately views the product list. The write goes to the primary, the read goes to the replica. If the replica is 50ms behind, the product isn't in the list yet. How would you solve this? (Hint: "read-your-writes" consistency — route recent writers to the primary for a short window)

5. **Our app uses 10 HikariCP connections per server.** In Exercise 01, 10 connections to an in-memory H2 was a bottleneck for Tomcat's 50 threads. Now 10 connections go to PostgreSQL over the network. Is 10 still the right number? What factors should determine the connection pool size? (Hint: PostgreSQL's CPU cores, query duration, number of app servers)

6. **Each app server has two connection pools in Part C (primary + replica, 10 each).** That's 3 servers × 20 connections = 60 connections to the primary and replicas combined. Is this sustainable with 10 app servers? How does PgBouncer help? (Hint: PgBouncer multiplexes hundreds of app connections onto a few dozen PostgreSQL connections)

7. **The DataSeeder race condition created 15,000 products instead of 5,000.** How would you prevent this? Why does `productRepository.count() == 0` not work as a reliable guard when three servers start simultaneously? (Hint: transaction isolation — all three see count=0 before any of them commit their inserts)

---

## Bonus Challenges

- **Add PgBouncer** between the app servers and PostgreSQL. Run the bottleneck test again — does connection pooling help?

- **Add a `pg_trgm` index** on the products table and re-run the search-heavy load test:
  ```sql
  CREATE EXTENSION IF NOT EXISTS pg_trgm;
  CREATE INDEX idx_products_name_trgm ON products USING gin (name gin_trgm_ops);
  CREATE INDEX idx_products_desc_trgm ON products USING gin (description gin_trgm_ops);
  ```

- **Measure replication lag under write-heavy load:** Increase the write percentage in the k6 script to 50% and watch `replay_lag_bytes` grow on the replicas.

- **Route reads to both replicas:** Modify `DataSourceConfig` to round-robin between `postgres-replica-1` and `postgres-replica-2`, then re-run Test 4 and compare.

---

## What's Next?

Read replicas helped distribute read load, but the primary still handles all writes. Under write-heavy workloads, the primary becomes the bottleneck again. And we haven't addressed another common problem: the same data gets queried repeatedly (product lists, popular searches).

Exercise 04 introduces **caching with Redis** — storing frequently-read data in memory so it never hits the database at all. This dramatically reduces both read latency and database load.
