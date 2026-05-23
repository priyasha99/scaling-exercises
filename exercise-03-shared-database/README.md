# Exercise 03: Shared Database — The Next Bottleneck

**Goal:** Discover what happens when you fix Exercise 02's data inconsistency by adding a shared database. Spoiler: the database becomes the new bottleneck. Then see how read replicas help distribute the load.

**What you'll learn:**
- Shared PostgreSQL solves data inconsistency but introduces a new bottleneck
- Adding more app servers doesn't help when the database is the ceiling
- How to monitor PostgreSQL to identify it as the bottleneck
- How PostgreSQL streaming replication works
- How Spring Boot read/write routing splits traffic between primary and replicas
- The trade-off between consistency and read performance (replication lag)

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
- **DataSeeder:** Added comment about the race condition when multiple servers try to seed simultaneously

JPA handles the rest — same entity, same repository, same queries.

### Running Part A

#### Step 1: Start the cluster

```bash
cd exercise-03-shared-database

docker compose up --build
```

Wait for all containers to report healthy. PostgreSQL starts first (10s), then app servers boot (~30-40s each for JVM + DB migration + data seeding).

#### Step 2: Verify shared data

```bash
# Create a product (goes to any server)
curl -X POST http://localhost/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Shared DB Test","description":"This should be visible everywhere","price":42.00,"category":"Electronics","stockQuantity":100}'

# Read it back multiple times — different servers, same data
curl -s http://localhost/api/products/5001 -D - | grep X-Server-Id
curl -s http://localhost/api/products/5001 -D - | grep X-Server-Id
curl -s http://localhost/api/products/5001 -D - | grep X-Server-Id
```

Each request goes to a different server (check the `X-Server-Id` header), but the product is always there. Problem solved!

#### Step 3: Run baseline test

```bash
cd loadtest
K6_WEB_DASHBOARD=true k6 run 01-shared-db-baseline.js
```

This light test (50 VUs) verifies data consistency under load and establishes baseline performance. Compare these numbers against Exercise 02's results — PostgreSQL will be slower than H2 in-memory, even under light load. That's the cost of durability and shared state.

#### Step 4: Monitor everything

```bash
# In a separate terminal
docker stats

# Check PostgreSQL connections
docker exec exercise-03-shared-database-postgres-1 \
  psql -U app -d productdb -c "SELECT count(*) FROM pg_stat_activity WHERE state = 'active';"
```

---

## Part B: The Database Becomes the Bottleneck

Now let's throw serious load at the shared database setup.

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

**What you should see:**
- PostgreSQL CPU near 100% while app servers have headroom
- Many connections in 'active' state, some queries taking seconds
- The LIKE search queries dominate the slow query list
- Response times much higher than Exercise 02 with the same load

### Reference Results

Compare against Exercise 02's Test 1 (3 servers + H2):

| Metric | Ex02: 3 servers + H2 | Ex03: 3 servers + PostgreSQL |
|---|---|---|
| Median | 248ms | _your results_ |
| p95 | 5,345ms | _your results_ |
| Errors | 0.03% | _your results_ |
| Throughput | 166.7 req/s | _your results_ |
| Timeouts | 3 | _your results_ |

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

**Why it doesn't help:**
- 3 servers × 10 connections = 30 connections to PostgreSQL
- 6 servers × 10 connections = 60 connections — exceeds `max_connections=50`!
- Even without hitting the connection limit, all 6 servers are waiting for the same PostgreSQL to process queries
- It's like adding more waiters to a restaurant where the kitchen is the bottleneck — more waiters doesn't make the food cook faster

**Check PostgreSQL connections to see the saturation:**

```bash
# This shows how many of the 50 max connections are used
docker exec exercise-03-shared-database-postgres-1 \
  psql -U app -d productdb -c "
    SELECT max_conn, used, max_conn - used AS available
    FROM (SELECT count(*) used FROM pg_stat_activity) t1,
         (SELECT setting::int max_conn FROM pg_settings WHERE name = 'max_connections') t2;"
```

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
1. Primary PostgreSQL starts and initializes
2. Replicas clone the primary using `pg_basebackup` (~10-20s)
3. App servers boot and connect to both primary and replica

#### Step 2: Verify replication is working

```bash
# Check replication status on primary
docker exec exercise-03-shared-database-postgres-primary-1 \
  psql -U app -d productdb -c "SELECT * FROM pg_stat_replication;"

# Should show 2 replica connections with state = 'streaming'

# Create a product (goes to primary)
curl -X POST http://localhost/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Replication Test","description":"Should appear on replicas within milliseconds","price":99.99,"category":"Electronics","stockQuantity":50}'

# Check it exists on a replica (small delay possible)
docker exec exercise-03-shared-database-postgres-replica-1-1 \
  psql -U app -d productdb -c "SELECT id, name FROM products ORDER BY id DESC LIMIT 3;"
```

#### Step 3: Run the benchmark

```bash
cd loadtest
K6_WEB_DASHBOARD=true k6 run 04-read-replicas.js
```

#### Step 4: Compare results

| Metric | Single PostgreSQL (Test 2) | Primary + 2 Replicas (Test 4) |
|---|---|---|
| Median | _your results_ | _your results_ |
| p95 | _your results_ | _your results_ |
| Errors | _your results_ | _your results_ |
| Throughput | _your results_ | _your results_ |
| Timeouts | _your results_ | _your results_ |

**Expected improvements:**
- Read-heavy queries (search, stats, category) should be faster because they hit the replica, not the primary
- Write latency should improve because the primary has less read contention
- Overall throughput should increase
- PostgreSQL primary CPU should be lower (only handling writes + WAL streaming)

#### Step 5: Monitor replication lag

```bash
# On the primary, check how far behind replicas are
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

Under heavy load, you might see `replay_lag_bytes` increase — the replica falls behind the primary. This is replication lag. A product created on the primary might not be visible on the replica for a few milliseconds. For most applications, this is acceptable. For financial transactions or inventory, it's not.

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

1. **In Exercise 02, scaling from 1 to 3 servers gave 4.6x throughput. In Exercise 03, scaling from 3 to 6 servers gave barely any improvement.** Why? What changed between the two exercises that made adding servers effective in one and useless in the other?

2. **PostgreSQL's `max_connections` is set to 50. With 6 servers × 10 HikariCP connections = 60.** What error do you see? How would you handle this in production? (Hint: PgBouncer — a connection pooler that sits between your app and PostgreSQL)

3. **The LIKE search query was fast on H2 but slow on PostgreSQL.** Why? H2 ran in the JVM's heap memory. PostgreSQL reads from disk (or shared_buffers if cached). What database feature would speed up text search? (Hint: full-text search indexes, or `pg_trgm` extension for LIKE optimization)

4. **Read replicas have replication lag.** A user creates a product, then immediately views the product list. The write goes to the primary, the read goes to the replica. If the replica is 50ms behind, the product isn't in the list yet. How would you solve this? (Hint: "read-your-writes" consistency — route recent writers to the primary for a short window)

5. **Our app uses 10 HikariCP connections per server.** In Exercise 01, 10 connections to an in-memory H2 was a bottleneck for Tomcat's 50 threads. Now 10 connections go to PostgreSQL over the network. Is 10 still the right number? What factors should determine the connection pool size? (Hint: PostgreSQL's CPU cores, query duration, number of app servers)

6. **Each app server has two connection pools in Part C (primary + replica, 10 each).** That's 3 servers × 20 connections = 60 connections to the primary and replicas combined. Is this sustainable with 10 app servers? How does PgBouncer help? (Hint: PgBouncer multiplexes hundreds of app connections onto a few dozen PostgreSQL connections)

---

## Bonus Challenges

- **Add PgBouncer** between the app servers and PostgreSQL. Run the bottleneck test again — does connection pooling help?

- **Add a `pg_trgm` index** on the products table and re-run the search-heavy load test:
  ```sql
  CREATE EXTENSION IF NOT EXISTS pg_trgm;
  CREATE INDEX idx_products_name_trgm ON products USING gin (name gin_trgm_ops);
  CREATE INDEX idx_products_desc_trgm ON products USING gin (description gin_trgm_ops);
  ```

- **Check slow query logs:**
  ```bash
  docker exec exercise-03-shared-database-postgres-1 \
    cat /var/lib/postgresql/data/log/*.log | grep "duration:" | head -20
  ```

- **Measure replication lag under write-heavy load:** Increase the write percentage in the k6 script to 50% and watch `replay_lag_bytes` grow on the replicas.

---

## What's Next?

Read replicas helped distribute read load, but the primary still handles all writes. Under write-heavy workloads, the primary becomes the bottleneck again. And we haven't addressed another common problem: the same data gets queried repeatedly (product lists, popular searches).

Exercise 04 introduces **caching with Redis** — storing frequently-read data in memory so it never hits the database at all. This dramatically reduces both read latency and database load.
