# Exercise 07: Rate Limiting and Backpressure

**Building on:** Exercise 06 (JWT + Redis + RabbitMQ + Read Replicas)

## What This Exercise Adds

Exercises 02-06 focused on making the system handle more traffic: more servers, caching, async processing. But what happens when traffic exceeds what the system can handle? Without limits, every component degrades — response times spike, errors cascade, and the system becomes unusable for everyone.

Rate limiting protects the system by rejecting excess traffic with 429 Too Many Requests before it reaches the expensive parts (database, message queue). Backpressure tells clients when to retry.

---

## Key Concepts

### Token Bucket Algorithm

Imagine a bucket that holds tokens. It fills up at a fixed rate (e.g., 10 tokens/second). Each request takes one token. If the bucket is empty, the request is rejected.

```
Bucket: [●●●●●●●●●●] capacity = 10, refill = 10/sec

Request 1: take 1 → [●●●●●●●●●○] allowed (9 remaining)
Request 2: take 1 → [●●●●●●●●○○] allowed (8 remaining)
...burst of 8 more...
Request 10: take 1 → [○○○○○○○○○○] allowed (0 remaining)
Request 11: take 1 → REJECTED (429 Too Many Requests)

...1 second later, 10 tokens refill...
Request 12: take 1 → [●●●●●●●●●○] allowed again
```

**Why token bucket over fixed window?** A fixed window (e.g., 100 requests per minute) allows bursts at window boundaries: 100 requests at 0:59, 100 more at 1:00 = 200 in 2 seconds. Token bucket smooths this — the sustained rate is enforced regardless of when requests arrive.

### Distributed Rate Limiting

We have 3 servers. If each tracked limits independently, a user could make 50 requests to each server = 150 total while the intended limit is 50. By storing the token bucket in Redis, all servers share the same counter.

The bucket state (tokens remaining, last refill time) is stored as a Redis hash. A Lua script reads the state, calculates refill, checks/deducts tokens, and writes back — all atomically. No race condition between concurrent requests on different servers.

### Three Tiers

| Tier | Key | Capacity | Rate | Purpose |
|------|-----|----------|------|---------|
| Per-user | `rate_limit::user::{username}` | 50 | 10/s | Fair per-user limits |
| Per-IP | `rate_limit::ip::{ip}` | 30 | 5/s | Limit anonymous traffic |
| Global | `rate_limit::global` | 500 | 200/s | System-wide backpressure |
| Admin | `rate_limit::user::{admin}` | 200 | 50/s | Elevated admin limits |

Every request checks **two** buckets: global first, then per-user or per-IP. If either is exceeded, the request gets 429.

---

## Architecture

```
Client → [ Nginx ] → [ App 1 ] → JWT filter → Rate limit filter → Controller
                    → [ App 2 ]                      ↓
                    → [ App 3 ]               [ Redis: token buckets ]
                                               (shared across all servers)
```

Filter chain order:
1. **JwtAuthenticationFilter** — extracts JWT, sets SecurityContext
2. **RateLimitFilter** — checks token bucket in Redis via Lua script
3. **Spring Security** — checks endpoint permissions
4. **Controller** — handles the request

The rate limit filter runs after JWT so it knows the authenticated username for per-user limiting.

---

## New Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/rate-limit/config` | Public | Current rate limit configuration |
| GET | `/api/rate-limit/metrics` | Public | Rejection stats by tier |

## Response Headers

Every response includes rate limit headers:

```
X-RateLimit-Limit: 50          ← bucket capacity
X-RateLimit-Remaining: 42      ← tokens left
```

On 429 responses:
```
Retry-After: 3                 ← seconds to wait
X-RateLimit-Tier: user         ← which tier was exceeded
```

---

## Part A: Manual Testing

### Step 1: Start the stack

```bash
cd exercise-07-rate-limiting
docker compose up --build
```

### Step 2: Login

```bash
TOKEN=$(curl -s -X POST http://localhost/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"user123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")
```

### Step 3: Check rate limit config

```bash
curl -s http://localhost/api/rate-limit/config | jq .
```

### Step 4: Make requests and watch headers

```bash
# Watch X-RateLimit-Remaining count down
for i in {1..5}; do
  curl -si http://localhost/api/products/stats/Electronics \
    -H "Authorization: Bearer $TOKEN" \
    -H "Connection: close" 2>/dev/null | grep -E "X-RateLimit|HTTP/"
  echo "---"
done
```

### Step 5: Trigger a 429

```bash
# Rapid-fire requests to exhaust the per-user bucket
for i in {1..60}; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    http://localhost/api/products/stats/Electronics \
    -H "Authorization: Bearer $TOKEN")
  echo "Request $i: $STATUS"
  if [ "$STATUS" = "429" ]; then
    echo "Rate limited! Checking Retry-After..."
    curl -si http://localhost/api/products/stats/Electronics \
      -H "Authorization: Bearer $TOKEN" 2>/dev/null | grep -E "Retry-After|X-RateLimit"
    break
  fi
done
```

### Step 6: Test per-IP limiting (unauthenticated)

```bash
# No auth token — uses per-IP limit (stricter: 30 burst, 5/sec)
for i in {1..35}; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    http://localhost/api/products/1)
  echo "Request $i: $STATUS"
done
```

### Step 7: Test admin gets higher limits

```bash
ADMIN_TOKEN=$(curl -s -X POST http://localhost/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# Admin has 200 burst — this shouldn't trigger 429
for i in {1..55}; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    http://localhost/api/products/stats/Electronics \
    -H "Authorization: Bearer $ADMIN_TOKEN")
  echo "Request $i: $STATUS"
done
```

### Step 8: Check rate limit metrics

```bash
curl -s http://localhost/api/rate-limit/metrics | jq .
```

---

## Part B: Load Testing

### Test 1: No Rate Limiting (Baseline)

```bash
docker compose down
RATE_LIMIT_ENABLED=false docker compose up --build

K6_WEB_DASHBOARD=true k6 run loadtest/01-no-rate-limit-baseline.js
```

| Metric | Result |
|--------|--------|
| Median latency | 1,040ms |
| P95 latency | 3,130ms |
| Requests/sec | 200 req/s |
| Error rate | **0.00%** |
| 429 responses | 0 |

```bash
docker compose down
```

### Test 2: With Rate Limiting

```bash
docker compose up --build

K6_WEB_DASHBOARD=true k6 run loadtest/02-with-rate-limiting.js
```

| Metric | Result |
|--------|--------|
| Median latency | **33ms** (429s are instant) |
| P95 latency | 1,900ms |
| Total HTTP req/s | 462 req/s |
| Server error rate | **0.00%** |
| 429 responses (total) | **30,503** (71%) |
| 429 by user tier | 24,248 |
| 429 by IP tier | 5,620 |
| 429 by global tier | 635 |
| Successful requests | 12,407 (133 req/s) |

### Test 3: Tier Verification

```bash
K6_WEB_DASHBOARD=true k6 run loadtest/03-rate-limit-tiers.js
```

| Metric | Result |
|--------|--------|
| User rate limited | 11,438 |
| User allowed | 1,303 |
| Admin rate limited | 4,872 |
| Admin allowed | **2,617** (2x more than regular users) |
| IP rate limited | 6,884 |
| IP allowed | 475 (strictest tier) |
| Retry-After respected | **1,398** (wait + retry succeeded) |
| Custom error rate | **0.00%** |
| Median latency | 1.37ms (most are fast 429s) |

---

## Part C: Backpressure Behavior

### What is backpressure?

Backpressure is when a system tells upstream components to slow down. Without it, an overloaded system accepts everything, gets slower, and eventually crashes. With backpressure, it rejects excess early and stays healthy for the traffic it can handle.

Our rate limiter implements backpressure at two levels:

**Per-user/IP backpressure:** Prevents any single client from monopolizing the system. Other clients continue to work normally even if one client is hammering the API.

**Global backpressure:** When total traffic exceeds system capacity, ALL clients get 429s. This protects the database, message queue, and other backend components from being overwhelmed. The system degrades gracefully — rejected requests get a fast 429 instead of a slow timeout.

### Fail-open behavior

If Redis is down, the rate limiter **allows all requests** (fail-open). This is a deliberate choice — it's better to serve requests without rate limiting than to block everything because the limiter's backend is unavailable.

---

## New Files

```
src/main/java/com/scaling/exercise/
├── ratelimit/
│   ├── RateLimitConfig.java     # Configuration properties (capacity, refill rates)
│   ├── RateLimiterService.java  # Executes Lua script against Redis
│   ├── RateLimitFilter.java     # Servlet filter — checks buckets, returns 429
│   ├── RateLimitResult.java     # Result POJO (allowed, remaining, retry-after)
│   ├── RateLimitMetrics.java    # Rejection counters by tier
│   └── RateLimitController.java # Monitoring endpoints (/config, /metrics)
src/main/resources/
├── scripts/
│   └── token_bucket.lua         # Atomic token bucket algorithm in Redis Lua
```

---

## The Full Architecture (Exercise 01 → 07)

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
```

Each exercise addressed a different scaling challenge:
- Ex02: Single server → horizontal scaling
- Ex03: Single DB → read replicas
- Ex04: Redundant queries → caching
- Ex05: Sticky sessions → stateless JWT
- Ex06: Blocking writes → async processing
- Ex07: Uncontrolled traffic → rate limiting + backpressure

---

## Cleanup

```bash
docker compose down -v
```

---

## Discussion Questions

1. **Fail-open vs fail-closed:** Our limiter allows all requests if Redis is down. What are the trade-offs? When would you want fail-closed instead?

2. **Per-user vs per-API-key:** We limit by username. In a public API, you'd limit by API key. What changes? (Hint: unauthenticated users all share the same "anonymous" bucket)

3. **The global limit is 200 req/s, but our system can handle ~80 req/s.** Should the global limit match actual capacity? What happens if it's set too low (wasted capacity) or too high (no protection)?

4. **Rate limiting at Nginx vs application:** Nginx has built-in rate limiting (`limit_req`). Why did we implement it in the application instead? (Hint: per-user limits need JWT context that Nginx doesn't have)

5. **Token bucket allows bursts.** A user with a full bucket can send 50 requests instantly. Is this a problem? When would you want a leaky bucket (constant rate, no burst) instead?
