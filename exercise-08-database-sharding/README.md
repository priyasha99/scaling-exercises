# Exercise 08: Database Sharding

**Building on:** Exercise 07 (JWT + Redis + RabbitMQ + Rate Limiting)

## What This Exercise Adds

Exercises 02-07 scaled the application layer: more servers, caching, async processing, rate limiting. But the database is still a single PostgreSQL instance. At some point, one database can't handle the volume — too many connections, too much data, too many writes. Vertical scaling (bigger machine) has limits.

Sharding splits data across multiple database instances. Each shard holds a subset of the data. Queries that know the shard key route to one shard; queries that don't must fan out to all shards.

---

## Key Concepts

### Hash-Based Sharding

We shard on the `category` field using a hash function:

```
shard = Math.abs(category.hashCode()) % 2
```

```
"Electronics" → hashCode() → shard-?
"Books"       → hashCode() → shard-?
"Clothing"    → hashCode() → shard-?
"Health"      → hashCode() → shard-?
...
```

Every product in "Electronics" lives on the same shard. The hash is deterministic — the same category always maps to the same shard, regardless of which server computes it. Check `/api/shard/config` to see the actual mapping.

**Why hash-based?** Even distribution without a lookup table. The shard is computed, not stored. No single point of failure (unlike directory-based sharding).

**Why category as shard key?** The most common queries (browse by category, category stats) route to one shard. Products in the same category are co-located — joins and aggregations within a category are local to one database.

### Single-Shard vs Cross-Shard Queries

| Query Type | Shard Key Known? | Shards Hit | Example |
|---|---|---|---|
| Single-shard | Yes | 1 | GET /products/stats/Electronics |
| Cross-shard | No | ALL | GET /products/search?q=Premium |

**Single-shard queries** are fast — one database round trip, just like a non-sharded system. The application sets `ShardContext` based on the category, and the `ShardRoutingDataSource` routes the connection.

**Cross-shard queries** (scatter-gather) fan out to every shard sequentially, then merge results in the application. With 2 shards, that's 2x the database round trips. With 10 shards, 10x. This is the fundamental cost of sharding.

### The ID Collision Problem

With auto-increment IDs on separate databases, both shards generate IDs starting from 1. Product ID 42 exists on both shards (different products). This means:

- `GET /products/42` must check both shards (returns the first match)
- IDs are shard-local, not globally unique

In production, you'd solve this with UUIDs, Snowflake IDs, or shard-prefixed sequences. For this exercise, we accept the collision to demonstrate the problem.

---

## Architecture

```
Client → [ Nginx ] → [ App 1 ] → JWT → Rate Limit → ShardRouter
                    → [ App 2 ]                          ↓
                    → [ App 3 ]              ┌───────────┴───────────┐
                                        [ Shard 0 ]          [ Shard 1 ]
                                        PostgreSQL            PostgreSQL
                                        (categories A)        (categories B)
```

All 3 app servers connect to BOTH shards. The `ShardRoutingDataSource` routes each query to the correct shard based on the thread-local `ShardContext`.

### Routing Flow

```
1. Controller receives request with category "Electronics"
2. ProductService sets ShardContext.setCurrentShard("shard-0")
3. Repository.findByCategory("Electronics") triggers JDBC
4. LazyConnectionDataSourceProxy acquires connection NOW
5. ShardRoutingDataSource.determineCurrentLookupKey() reads "shard-0"
6. Connection from Shard0-HikariPool is returned
7. Query executes against postgres-shard-0
8. ShardContext.clear() in finally block
```

---

## New Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/shard/config` | Public | Shard config and category mapping |
| GET | `/api/shard/metrics` | Public | Query distribution across shards |

---

## Part A: Manual Testing

### Step 1: Start the stack (sharded)

```bash
cd exercise-08-database-sharding
docker compose up --build
```

### Step 2: Check shard configuration

```bash
curl -s http://localhost/api/shard/config | jq .
```

This shows which categories map to which shard.

### Step 3: Login

```bash
TOKEN=$(curl -s -X POST http://localhost/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"user123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")
```

### Step 4: Create products on different shards

```bash
# Create a product — check /api/shard/config to see which shard this goes to
curl -s -X POST http://localhost/api/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Shard Test Widget","description":"Testing shard routing","price":29.99,"category":"Electronics","stockQuantity":10}' | jq .

# Create a product in a different category (likely different shard)
curl -s -X POST http://localhost/api/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Shard Test Book","description":"Testing shard routing","price":19.99,"category":"Books","stockQuantity":5}' | jq .
```

### Step 5: Verify single-shard queries

```bash
# Category stats — should route to ONE shard
curl -s http://localhost/api/products/stats/Electronics \
  -H "Authorization: Bearer $TOKEN" | jq .

curl -s http://localhost/api/products/stats/Books \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### Step 6: Test cross-shard queries

```bash
# Search — fans out to ALL shards, merges results
curl -s "http://localhost/api/products/search?q=Premium" \
  -H "Authorization: Bearer $TOKEN" | jq 'length'
```

### Step 7: Check shard metrics

```bash
curl -s http://localhost/api/shard/metrics | jq .
```

### Step 8: Verify data distribution in pgAdmin

Open http://localhost:5050 (admin@admin.com / admin).

Add both servers:
- Shard 0: host=postgres-shard-0, port=5432, user=app, password=app_password
- Shard 1: host=postgres-shard-1, port=5432, user=app, password=app_password

Run on each shard:
```sql
SELECT category, COUNT(*) FROM products GROUP BY category ORDER BY category;
```

Each shard should only have products for the categories that hash to it.

### Step 9: Check health endpoint

```bash
curl -s http://localhost/api/products/health | jq .
```

Should show `"shardingEnabled": true`.

---

## Part B: Load Testing

### Test 1: Single Database Baseline

```bash
docker compose down -v
SHARDING_ENABLED=false docker compose up --build

K6_WEB_DASHBOARD=true k6 run loadtest/01-single-db-baseline.js
```

| Metric | Result |
|--------|--------|
| Median latency | |
| P95 latency | |
| Requests/sec | |
| Error rate | |
| Category stats latency (median) | |
| Search latency (median) | |
| Write latency (median) | |

```bash
docker compose down -v
```

### Test 2: Sharded (2 PostgreSQL Instances)

```bash
docker compose up --build

K6_WEB_DASHBOARD=true k6 run loadtest/02-sharded-comparison.js
```

| Metric | Result |
|--------|--------|
| Median latency | |
| P95 latency | |
| Requests/sec | |
| Error rate | |
| Category stats latency (median) | |
| Search latency (median) | |
| Write latency (median) | |
| Single-shard queries | |
| Cross-shard queries | |

### Test 3: Cross-Shard vs Single-Shard Cost

```bash
K6_WEB_DASHBOARD=true k6 run loadtest/03-cross-shard-cost.js
```

| Metric | Result |
|--------|--------|
| Single-shard latency (median) | |
| Cross-shard latency (median) | |
| Cross-shard / single-shard ratio | |
| Single-shard queries | |
| Cross-shard queries | |
| Write latency (median) | |
| Custom error rate | |

---

## Part C: Sharding Trade-offs and Rebalancing

### When to Shard

Sharding is a last resort. Before sharding, exhaust simpler options:
1. **Vertical scaling** — bigger machine, more RAM, faster disks
2. **Read replicas** — split read/write traffic (Exercise 03)
3. **Caching** — reduce DB load with Redis (Exercise 04)
4. **Connection pooling** — optimize connection usage (HikariCP)

Shard when: the dataset exceeds what one machine can store, write throughput exceeds one machine's capacity, or you need data locality for regulatory reasons.

### The Rebalancing Problem

With 2 shards and a growing dataset, one shard might fill up faster. Adding a third shard means `hash % 3` instead of `hash % 2` — every category maps to a different shard. You'd need to move data between shards.

Strategies for rebalancing:
- **Consistent hashing**: minimizes data movement when adding/removing shards
- **Virtual shards**: map many virtual shards to fewer physical shards; rebalance by remapping virtual → physical
- **Double-write migration**: write to old and new shard during transition, then cut over

### Cross-Shard Joins

Our system avoids cross-shard joins by sharding on category — category queries are local. But what if you need to join products across categories? Options:

- **Denormalize**: store redundant data so joins aren't needed
- **Application-side join**: query both shards, join in the app layer
- **Search index**: use Elasticsearch for cross-shard full-text search
- **Reference tables**: replicate small tables (like users) to every shard

### Shard Key Selection

Bad shard keys create hot spots. If 90% of queries are for "Electronics" and it's all on one shard, that shard is overloaded while the other is idle. Good shard keys:

- Have high cardinality (many distinct values)
- Distribute queries evenly
- Are present in most queries (to avoid cross-shard fan-out)
- Don't change after creation (moving data between shards is expensive)

---

## New Files

```
src/main/java/com/scaling/exercise/
├── sharding/
│   ├── ShardContext.java          # Thread-local shard identifier
│   ├── ShardRoutingDataSource.java # AbstractRoutingDataSource for shards
│   ├── ShardDataSourceConfig.java  # Creates 2 HikariCP pools + routing
│   ├── ShardingService.java        # Hash-based shard determination
│   ├── ShardMetrics.java           # Per-shard query counters
│   └── ShardController.java        # Monitoring endpoints
```

## Modified Files

```
service/ProductService.java         # Shard-aware queries (single + cross-shard)
config/DataSeeder.java              # Distributes seed data across shards
messaging/ProductConsumer.java      # Routes async writes to correct shard
controller/ProductController.java   # Shows shardingEnabled in health
security/SecurityConfig.java        # Permits /api/shard/** endpoints
ratelimit/RateLimitFilter.java      # Excludes /api/shard/** from rate limiting
```

---

## The Full Architecture (Exercise 01 → 08)

```
Ex01: Client → [ App ] → [ H2 ]

Ex02: Client → [ Nginx ] → [ App 1..3 ] → [ PostgreSQL ]

Ex03: Client → [ Nginx ] → [ App 1..3 ] → [ PG Primary + Replica ]

Ex04: Client → [ Nginx ] → [ App 1..3 ] → [ Redis ] → [ PG ]

Ex05: Client → [ Nginx ] → [ App 1..3 ] → JWT → [ Redis ] → [ PG ]

Ex06: Client → [ Nginx ] → [ App 1..3 ] → JWT → [ RabbitMQ ] → [ Redis ] → [ PG ]

Ex07: Client → [ Nginx ] → [ App 1..3 ] → JWT → Rate Limit → [ RabbitMQ ]
                                                      ↓              → [ Redis ] → [ PG ]
                                              [ Redis: buckets ]

Ex08: Client → [ Nginx ] → [ App 1..3 ] → JWT → Rate Limit → ShardRouter
                                                                    ↓
                                              [ RabbitMQ ] → [ Redis ] → [ PG Shard 0 ]
                                                                       → [ PG Shard 1 ]
```

Each exercise addressed a different scaling challenge:
- Ex02: Single server → horizontal scaling
- Ex03: Single DB → read replicas
- Ex04: Redundant queries → caching
- Ex05: Sticky sessions → stateless JWT
- Ex06: Blocking writes → async processing
- Ex07: Uncontrolled traffic → rate limiting + backpressure
- Ex08: Single database → sharding

---

## Cleanup

```bash
docker compose down -v
```

---

## Discussion Questions

1. **Category as shard key works here because category-specific queries dominate.** What if the most common query was "get all products under $50"? How would you shard differently?

2. **We shard products but not users.** What happens if the users table grows to millions of rows? Would you shard it too? What would be the shard key?

3. **Cross-shard search fans out to all shards sequentially.** How would you parallelize this? What's the trade-off (hint: thread pool size vs DB connection pool)?

4. **Adding a third shard changes the hash function.** "Electronics" might move from shard-0 to shard-2. How would you handle the data migration without downtime?

5. **Our auto-increment IDs collide across shards.** Product ID 1 exists on both shards. How would you generate globally unique IDs? (Options: UUID, Snowflake, shard-prefixed sequences)
