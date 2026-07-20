# Exercise 06: Async Processing with RabbitMQ

**Building on:** Exercise 05 (JWT + Redis Caching + Read Replicas)

## What This Exercise Adds

Exercise 05 gave us stateless JWT auth — any server can handle any request. But product creation still blocks: the API thread waits for the DB insert and cache eviction before responding.

Exercise 06 introduces **async processing**. Instead of doing the work inline, the API publishes a message to RabbitMQ and returns immediately. A background consumer picks up the message and does the heavy lifting.

```
SYNC (Exercise 05):                    ASYNC (Exercise 06):
POST → DB insert → evict → 201         POST → publish to RabbitMQ → 202
       (50-200ms wait)                         (~1ms, done)

                                        Background:
                                        Consumer → DB insert → evict caches
```

## Architecture

```
Client → [ Nginx LB ] → [ App 1 ] ──publish──→ [ RabbitMQ ]
                       → [ App 2 ]                  │
                       → [ App 3 ]                   │ consume
                                                     ↓
                         [ Redis ]  ←── cache    [ Consumer threads ]
                         [ PostgreSQL ] ←────── INSERT product
```

Each app server is both a **producer** (API publishes messages) and a **consumer** (3-5 background threads process messages). With 3 servers, that's 9-15 consumers processing in parallel.

## Key Concepts

**Why async?** The API thread is the bottleneck. Under load, Tomcat's thread pool (50 threads) fills up with slow DB writes. With async, the POST handler finishes in ~1ms — the thread is immediately free for the next request.

**Message flow:**
1. API receives POST, creates a `ProductMessage` with a unique `requestId`
2. Publishes to RabbitMQ exchange (direct exchange, routing key `product.create`)
3. Returns 202 Accepted with `requestId` and `statusUrl`
4. Consumer picks up message from `product-creation-queue`
5. Saves product to PostgreSQL, evicts caches
6. Updates status in Redis (QUEUED → PROCESSING → COMPLETED)

**Dead Letter Queue (DLQ):** If processing fails after retries, the message goes to `product-creation-dlq` instead of being lost. An operator can inspect and replay failed messages.

**Status polling:** Client receives a `requestId` and can poll `GET /api/products/async/status/{requestId}` to check progress. Status entries expire after 10 minutes.

---

## New Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/products` | JWT | Creates product (sync or async based on toggle) |
| POST | `/api/products/async` | JWT | Always creates product async |
| GET | `/api/products/async/status/{requestId}` | Public | Poll async request status |
| GET | `/api/products/async/metrics` | Public | Async processing stats |

## Sync vs Async Toggle

The `ASYNC_ENABLED` environment variable controls `POST /api/products`:

| Value | POST /api/products | POST /api/products/async |
|-------|-------------------|------------------------|
| `true` (default) | Async (202) | Async (202) |
| `false` | Sync (201) | Async (202) |

This lets you benchmark the exact same endpoint in both modes.

---

## Part A: Manual Testing

### Step 1: Start the stack

```bash
cd exercise-06-async-processing

# Async mode (default)
docker compose up --build
```

Wait for all services to be healthy. RabbitMQ takes ~30s to start.

### Step 2: Login

```bash
TOKEN=$(curl -s -X POST http://localhost/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"user123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")
```

### Step 3: Create a product (async)

```bash
curl -s -X POST http://localhost/api/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Connection: close" \
  -d '{
    "name": "Async Widget",
    "description": "Created via async processing",
    "price": 29.99,
    "category": "Electronics",
    "stockQuantity": 50
  }' | jq .
```

Expected response (202 Accepted):
```json
{
  "requestId": "abc-123-...",
  "status": "QUEUED",
  "statusUrl": "/api/products/async/status/abc-123-...",
  "publishedBy": "container-id"
}
```

### Step 4: Poll status

```bash
# Replace with your requestId from step 3
curl -s http://localhost/api/products/async/status/<requestId> | jq .
```

Expected:
```json
{
  "requestId": "abc-123-...",
  "status": "COMPLETED",
  "processedBy": "container-id"
}
```

Note: `publishedBy` and `processedBy` will often be different servers — the API published the message, and a different server's consumer processed it.

### Step 5: Use the explicit async endpoint

```bash
curl -s -X POST http://localhost/api/products/async \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Explicit Async Widget",
    "description": "Always async regardless of toggle",
    "price": 19.99,
    "category": "Books",
    "stockQuantity": 100
  }' | jq .
```

### Step 6: Check async metrics

```bash
curl -s http://localhost/api/products/async/metrics | jq .
```

### Step 7: Check health (shows asyncEnabled flag)

```bash
curl -s http://localhost/api/products/health | jq .
```

### Step 8: View RabbitMQ Management UI

Open http://localhost:15672 in your browser (guest/guest).
- **Queues tab:** See `product-creation-queue` and `product-creation-dlq`
- **Message rates:** How fast messages are published and consumed

---

## Part B: Load Testing

### Test 1: Sync Baseline

```bash
# Stop current stack, restart in sync mode
docker compose down
ASYNC_ENABLED=false docker compose up --build

# Run sync baseline
K6_WEB_DASHBOARD=true k6 run loadtest/01-sync-baseline.js
```

| Metric | Result |
|--------|--------|
| Median latency | |
| P95 latency | |
| Requests/sec | |
| Error rate | |
| Write latency (median) | |

```bash
docker compose down
```

### Test 2: Async Comparison

```bash
# Restart in async mode (default)
docker compose up --build

# Run async comparison (same workload)
K6_WEB_DASHBOARD=true k6 run loadtest/02-async-comparison.js
```

| Metric | Result |
|--------|--------|
| Median latency | |
| P95 latency | |
| Requests/sec | |
| Error rate | |
| Write latency (median) | |
| Status polls completed | |

### Test 3: Async Lifecycle

```bash
K6_WEB_DASHBOARD=true k6 run loadtest/03-async-lifecycle.js
```

| Metric | Result |
|--------|--------|
| Median latency | |
| Error rate | |
| Async completed | |
| Async still pending | |
| Avg polls to complete | |
| Explicit async used | |

---

## Part C: DLQ and Monitoring

### View dead letter queue

```bash
# Check if any messages ended up in the DLQ
curl -s -u guest:guest http://localhost:15672/api/queues/%2f/product-creation-dlq | jq '{messages: .messages, consumers: .consumers}'
```

### View queue stats

```bash
curl -s -u guest:guest http://localhost:15672/api/queues/%2f/product-creation-queue | jq '{messages: .messages, consumers: .consumers, message_stats: .message_stats}'
```

### What to look for

- **Messages in DLQ:** If any messages failed processing 3 times, they end up here. Check them to understand failure patterns.
- **Consumer count:** Should be 9-15 (3-5 per server × 3 servers).
- **Message rates:** The RabbitMQ UI shows publish/deliver rates in real time.

---

## New Files

```
src/main/java/com/scaling/exercise/
├── messaging/
│   ├── RabbitConfig.java        # Exchange, queue, DLQ, bindings
│   ├── ProductMessage.java      # Message DTO (what travels through RabbitMQ)
│   ├── ProductProducer.java     # Publishes messages to RabbitMQ
│   ├── ProductConsumer.java     # @RabbitListener — processes messages in background
│   └── AsyncMetrics.java        # Published/consumed/failed counters
├── controller/
│   └── ProductController.java   # Updated: async endpoints, status polling, metrics
```

---

## What to Observe

1. **Write latency drops dramatically** — from 50-200ms (sync) to ~1ms (async publish)
2. **Throughput increases** — API threads aren't blocked by DB writes
3. **Different servers publish and consume** — check `publishedBy` vs `processedBy`
4. **RabbitMQ distributes messages** — 9-15 consumers process in parallel
5. **Status polling works across servers** — Redis stores status, any server can read it

---

## The Full Architecture (Exercise 01 → 06)

```
Ex01: Client → [ App ] → [ H2 ]

Ex02: Client → [ Nginx ] → [ App 1..3 ] → [ PostgreSQL ]

Ex03: Client → [ Nginx ] → [ App 1..3 ] → [ PG Primary ] (writes)
                                          → [ PG Replica ] (reads)

Ex04: Client → [ Nginx ] → [ App 1..3 ] → [ Redis ] (cache)
                                               ↓ miss
                                          → [ PG Primary/Replica ]

Ex05: Client → [ Nginx ] → [ App 1..3 ] → JWT verify (stateless!)
                                          → [ Redis ] (cache + blacklist)
                                               ↓ miss
                                          → [ PG Primary/Replica ]

Ex06: Client → [ Nginx ] → [ App 1..3 ] → JWT verify
                                          → [ RabbitMQ ] (async writes)
                                          → [ Redis ] (cache + status)
                                               ↓ miss
                                          → [ PG Primary/Replica ]
```

Each exercise removed a bottleneck:
- Ex02: Single server → multiple servers
- Ex03: Single DB → read replicas
- Ex04: Redundant queries → Redis caching
- Ex05: Sticky sessions → stateless JWT auth
- Ex06: Blocking writes → async processing

---

## Cleanup

```bash
docker compose down -v
# or
docker compose -f docker-compose.replicas.yml down -v
```

---

## Discussion Questions

1. **The client gets a 202 but doesn't know when the product is actually created.** Is this acceptable for all use cases? What about an e-commerce checkout where the user needs to see their order immediately?

2. **What happens if RabbitMQ goes down?** The API can't publish messages, so writes fail. Is this better or worse than the sync approach where DB failures also cause write failures?

3. **Our consumers run on the same servers as the API.** In a real system, would you run separate consumer-only instances? What are the trade-offs?

4. **Message ordering isn't guaranteed when multiple consumers process in parallel.** Does this matter for product creation? When would it matter? (Hint: think about updates to the same product)

5. **The status polling endpoint requires clients to repeatedly call the API.** What alternatives exist? (Hint: WebSockets, Server-Sent Events, webhooks)
