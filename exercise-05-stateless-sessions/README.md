# Exercise 05: Stateless Sessions with JWT

**Goal:** Replace server-side sessions with JWT (JSON Web Tokens) so any app server can handle any request. No sticky sessions, no shared session store for authentication. Then explore the hard parts: token refresh, logout/revocation, and role-based access control.

**What you'll learn:**
- Why server-side sessions break horizontal scaling
- How JWT enables stateless authentication across multiple servers
- The structure of a JWT (header, payload, signature)
- Access tokens vs refresh tokens and why you need both
- Token blacklisting for logout (using Redis)
- Role-based access control (RBAC) without database lookups
- The trade-offs of stateless auth (token size, revocation complexity)

**Prerequisites:** Complete Exercise 04 first. This exercise builds on the Redis + replicas setup.

---

## The Problem We're Solving

In Exercises 02-04, we scaled to 3 app servers behind a load balancer. But we never had authentication. Let's think about what happens when we add it.

### Server-Side Sessions (The Problem)

Traditional web frameworks store sessions in server memory:

```
User logs in on Server 1:
  Server 1 creates session: {id: "abc123", user: "alice", role: "ADMIN"}
  Server 1 stores it in memory
  Response: Set-Cookie: JSESSIONID=abc123

Next request (load balanced to Server 2):
  Cookie: JSESSIONID=abc123
  Server 2 looks up session "abc123" in its memory
  Not found! → 401 Unauthorized
  User is "logged out" — they have to log in again
```

The session lives in ONE server's memory. If the load balancer sends the next request to a different server, the session is gone.

### Common Workarounds (All Have Problems)

**Sticky sessions:** Configure the load balancer to always send the same user to the same server. This works, but defeats the purpose of load balancing — if Server 1 goes down, all its users lose their sessions. And one server might be overloaded while others are idle.

**Shared session store (Redis):** Store sessions in Redis so all servers can access them. This works, but adds a Redis lookup on every single request. It also makes Redis a single point of failure for authentication.

**Database sessions:** Store sessions in PostgreSQL. Works, but adds a database query on every request — we just spent Exercise 04 trying to REDUCE database load.

### JWT (The Solution)

A JWT is a signed token that contains the user's identity. The server doesn't need to store anything — the token IS the session.

```
User logs in on Server 1:
  Server 1 creates JWT: {sub: "alice", role: "ADMIN", exp: 1234568790}
  Server 1 signs it with a secret key
  Response: {"accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWI..."}

Next request (load balanced to Server 2):
  Header: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWI...
  Server 2 verifies the signature using the SAME secret key
  Valid! User is "alice" with role "ADMIN"
  No database lookup. No Redis lookup. No session store.
```

The key: all servers share the same JWT secret. A token signed by Server 1 is verifiable by Server 2 and Server 3. Truly stateless.

---

## Part A: Adding JWT Authentication (The Setup)

### Architecture

```
Client → [ Nginx ] → [ App 1 ] → JWT verify (no DB, no Redis)
                    → [ App 2 ]   ↓ valid
                    → [ App 3 ] → [ Redis ] (cache check)
                                      ↓ miss
                                  [ PG Primary ] (writes)
                                  [ PG Replica ]  (reads)
```

The JWT verification happens BEFORE anything else. It's pure computation — verify the HMAC signature using the shared secret key. No network call. No I/O. Takes microseconds.

### What Changed From Exercise 04

- **pom.xml:** Added `jjwt-api`, `jjwt-impl`, `jjwt-jackson` (JWT library), `spring-boot-starter-security`
- **User.java:** NEW — User entity with username, BCrypt password hash, role
- **UserRepository.java:** NEW — JPA repository for users
- **JwtService.java:** NEW — Token generation, validation, and blacklisting
- **JwtAuthenticationFilter.java:** NEW — Extracts and validates JWT on every request
- **SecurityConfig.java:** NEW — Stateless session policy, endpoint security rules
- **AuthController.java:** NEW — Login, register, refresh, logout, verify endpoints
- **AdminController.java:** NEW — Admin-only endpoints (role-based access)
- **DataSeeder.java:** Updated to create default users (admin, user, alice, bob)
- **application.properties:** Added JWT secret and token expiration config
- **docker-compose files:** Added JWT_SECRET environment variable

### Security Rules

| Endpoint | Access |
|---|---|
| `POST /api/auth/register` | Public |
| `POST /api/auth/login` | Public |
| `POST /api/auth/refresh` | Public (needs valid refresh token) |
| `POST /api/auth/logout` | Authenticated |
| `GET /api/auth/verify` | Public (shows token validity) |
| `GET /api/products/**` | Public (read-only product data) |
| `POST /api/products` | Authenticated (create products) |
| `GET /api/admin/**` | ADMIN role only |
| `GET /api/products/health` | Public (monitoring) |
| `GET /api/products/cache-stats` | Public (monitoring) |

### Running Part A

#### Step 1: Start the cluster

```bash
cd exercise-05-stateless-sessions

# With replicas + Redis + JWT
docker compose -f docker-compose.replicas.yml up --build
```

Wait for all containers to be healthy (~60 seconds).

#### Step 2: Register a user

```bash
curl -s -X POST http://localhost/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username": "testuser", "password": "test123", "role": "USER"}' | python3 -m json.tool
```

Response includes `accessToken`, `refreshToken`, and `serverId` (which server handled registration).

#### Step 3: Login

```bash
curl -s -X POST http://localhost/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin123"}' | python3 -m json.tool
```

Save the `accessToken` from the response.

#### Step 4: Prove stateless works — same token, different servers

```bash
# Store the token
TOKEN=$(curl -s -X POST http://localhost/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# Make 10 requests — watch X-Server-Id change, X-Auth-User stays "admin"
for i in {1..10}; do
  curl -si http://localhost/api/auth/verify \
    -H "Authorization: Bearer $TOKEN" \
    -H "Connection: close" 2>/dev/null | grep -E "X-Server-Id|X-Auth-User"
  echo "---"
done
```

You'll see the requests hitting different servers (round-robin), but the same user is authenticated on all of them. No sticky sessions.

**Note the `Connection: close` header.** Without it, Nginx reuses the same upstream TCP connection for rapid sequential requests (keepalive), so all requests would appear to hit the same server. `Connection: close` forces each request to open a new connection, making Nginx's round-robin visible. Under real load (many concurrent users), round-robin works naturally because different users arrive on different connections.

#### Step 5: Test unauthenticated access

```bash
# Public endpoint — works without token
curl -s http://localhost/api/products/health | python3 -m json.tool

# Protected endpoint — 401 without token
curl -s -X POST http://localhost/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"test","price":9.99,"category":"Books","stockQuantity":5}'

# Same endpoint with token — works
curl -s -X POST http://localhost/api/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"Auth Test Product","description":"Created with JWT","price":29.99,"category":"Books","stockQuantity":10}' | python3 -m json.tool
```

#### Step 6: Test role-based access

```bash
# Admin token — 200 OK (returns user list)
curl -s http://localhost/api/admin/users \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# User token — 403 Forbidden (empty response body)
USER_TOKEN=$(curl -s -X POST http://localhost/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "user", "password": "user123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# Use -si to see the 403 status code (the body is empty)
curl -si http://localhost/api/admin/users \
  -H "Authorization: Bearer $USER_TOKEN" | head -5
```

Spring Security returns 403 with an empty body by default for access denied. The admin token gets the full user list; the user token is blocked. All of this happens without a database lookup — the role is in the JWT.

---

## Part B: Stateless Sessions Under Load

### The Experiment

Run authenticated load test with 500 VUs. Each VU logs in once, gets a JWT, then makes requests with it. Watch the tokens work across all 3 servers.

#### Step 1: Flush Redis (cold cache start)

```bash
docker compose -f docker-compose.replicas.yml exec redis redis-cli FLUSHALL
```

#### Step 2: Run the baseline (no auth)

```bash
cd loadtest
K6_WEB_DASHBOARD=true k6 run 01-no-cache-baseline.js
```

#### Step 3: Run the authenticated test

```bash
K6_WEB_DASHBOARD=true k6 run 02-with-cache.js
```

#### Step 4: Compare results

The key comparison is baseline (no auth) vs authenticated. JWT adds a tiny overhead per request (signature verification), but the big wins are:

- No session store lookups
- No database queries for auth
- No sticky sessions (true round-robin load balancing)
- Tokens work on any server

### What to Observe

**Token verification is fast:** JWT signature verification is a pure CPU operation (HMAC-SHA256). It takes microseconds — far less than a Redis lookup or database query.

**No session store traffic:** Unlike shared sessions (Redis/DB), JWT auth adds ZERO network calls. The only time auth touches the database is on login/register.

**Round-robin works:** Watch the `X-Server-Id` headers. Requests from the same user are distributed across servers. With sticky sessions, all of one user's requests would go to one server.

### Reference Results — Test 1 vs Test 2

| Metric | Test 1 (No Auth) | Test 2 (With JWT) |
|---|---|---|
| Median | 3,690ms | 5,250ms |
| p95 | 9,090ms | 10,340ms |
| Throughput | 95 req/s | 86 req/s |
| Errors | 3.59% | **0.41%** |
| Cache hit rate | ~79% | — |
| Auth failures (401) | — | **0** |

**Key observations:**

The error rate dropped from 3.59% to 0.41%. Zero authentication failures — `not 401 (auth works)` passed 100%. All 39 errors in Test 2 were the usual timeout/connection issues, not JWT problems.

The throughput/median difference is due to the workload mix, not JWT overhead. Test 2 includes 10% requests to the lightweight `/api/auth/verify` endpoint and 10% writes (cheap INSERTs), while Test 1 is 100% heavy reads (stats, search, product lookups). JWT signature verification itself is sub-millisecond — invisible next to database queries taking hundreds of milliseconds.

---

## Part C: Token Security (The Hard Parts)

JWT makes authentication stateless, but stateless has trade-offs. This section addresses the hard questions.

### Problem 1: Token Expiry

JWTs have an expiration time (`exp` claim). Our access tokens expire after 15 minutes. After that, the user gets a 401 and needs to re-authenticate.

**Why short-lived tokens?** If a token is stolen, the attacker can use it until it expires. A 15-minute window limits the damage. A 24-hour window is much worse.

### Problem 2: Token Refresh

Re-entering credentials every 15 minutes is terrible UX. Refresh tokens solve this:

```
Login → access token (15 min) + refresh token (24 hours)

...14 minutes later...

POST /api/auth/refresh with refresh token → new access token (15 min)

...14 more minutes later...

POST /api/auth/refresh → another new access token
```

The refresh token has a longer lifetime (24 hours) and is only sent to the `/refresh` endpoint — it's never sent with regular API requests, reducing its exposure.

```bash
# Refresh the token
curl -s -X POST http://localhost/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\": \"$REFRESH_TOKEN\"}" | python3 -m json.tool
```

### Problem 3: Logout (Token Revocation)

This is the hardest problem with JWTs. A JWT is valid until it expires — the server can't "unset" it because the server doesn't store it. If a user logs out, the token is still mathematically valid.

**Our solution: Redis blacklist**

When a user logs out, we add the token's unique ID (jti claim) to a Redis set. Every subsequent request checks the blacklist before accepting the token.

```bash
# Logout
curl -s -X POST http://localhost/api/auth/logout \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Try using the old token — should get 401
curl -s http://localhost/api/auth/verify \
  -H "Authorization: Bearer $TOKEN"
```

**Why this is a compromise:** The blacklist reintroduces shared state (Redis). But the blacklist is much smaller than a full session store — only recently-revoked tokens, and they auto-expire when the token would have expired anyway.

### Problem 4: Role-Based Access Control

The JWT contains the user's role (`role` claim). Spring Security checks this claim before allowing access to protected endpoints.

```bash
# Admin can access /api/admin/*
curl -s http://localhost/api/admin/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -m json.tool

# Regular user gets 403
curl -s http://localhost/api/admin/users \
  -H "Authorization: Bearer $USER_TOKEN"
```

**The stale role problem:** If an admin demotes a user to USER, the user's existing token still has `role: "ADMIN"` until it expires. This is a fundamental JWT trade-off — the token is a snapshot of the user's state at login time.

Solutions: short token lifetimes (15 min), force re-login on role change, or check role in the database on sensitive operations (trading statelessness for consistency).

### Running Part C Load Test

```bash
# This test exercises the full token lifecycle:
# login → requests → refresh → more requests → logout → verify blacklist
K6_WEB_DASHBOARD=true k6 run 03-cache-invalidation.js
```

Watch the custom metrics:
- `token_refreshes` — how many tokens were refreshed
- `logouts` — how many logout+blacklist operations
- `admin_access_denied` — USER tokens correctly rejected from admin endpoints
- `admin_access_granted` — ADMIN tokens correctly accepted

### Reference Results — Test 3 (Token Lifecycle, 300 VUs)

| Metric | Value |
|---|---|
| Median | 1,090ms |
| p95 | 10,180ms |
| Throughput | 78 req/s |
| Custom error rate | **0.00%** |
| Logins | 100% successful |
| Token refreshes | 566 (100% successful) |
| Logouts + blacklist | 351 (100% successful) |
| Old token rejected after logout | **100%** |
| Admin access denied (USER role) | 941 |
| Admin access granted (ADMIN role) | 268 |

**Key observations:**

The `http_req_failed` shows 15.32% and `checks_failed` shows 11.42%, but these are expected 403 responses from USER tokens hitting `/api/admin/dashboard` — that's correct behavior, not errors. The custom `error_rate` (which excludes 403s) is **0.00%**.

The admin access split (~78% denied, ~22% granted) matches our user distribution: 3 of 4 test users have USER role, 1 has ADMIN role. Role-based access works without any database lookup — the role is embedded in the JWT.

Token blacklisting works perfectly: after logout, 100% of attempts to reuse the old token were rejected. The blacklist entry in Redis is shared across all servers and auto-expires when the token would have expired anyway.

---

## JWT vs Server-Side Sessions: The Trade-Offs

| Aspect | Server-Side Sessions | JWT |
|---|---|---|
| Scaling | Needs sticky sessions or shared store | Any server, no shared state |
| Auth lookup per request | Redis/DB lookup | CPU only (signature verify) |
| Logout | Delete session — immediate | Blacklist needed — adds complexity |
| Token size | Small cookie (session ID) | Larger (~500 bytes for JWT) |
| Revocation | Instant (delete from store) | Requires blacklist infrastructure |
| User data freshness | Always current (from store) | Snapshot at login time (stale until refresh) |
| Infrastructure | Session store (Redis/DB) | Shared secret (config only) |
| Failure mode | Session store down = all users logged out | Secret compromised = all tokens valid |

JWT is the right choice when you need horizontal scaling without infrastructure overhead. Server-side sessions are simpler when you have a single server or need instant revocation.

---

## The Full Architecture (Exercise 01 → 05)

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
```

Each exercise removed a bottleneck:
- Ex02: Single server → multiple servers
- Ex03: Single DB → read replicas
- Ex04: Redundant queries → Redis caching
- Ex05: Sticky sessions → stateless JWT auth

---

## Cleanup

```bash
# Stop the replicas + Redis + JWT setup
docker compose -f docker-compose.replicas.yml down -v

# Or stop the single-PG setup
docker compose down -v
```

---

## Discussion Questions

1. **JWT tokens are ~500 bytes. Session cookies are ~30 bytes.** Every request carries the full JWT. At what point does the bandwidth overhead of JWTs matter? (Hint: think about mobile clients on slow networks, or APIs that handle millions of requests per second)

2. **Our access tokens expire in 15 minutes.** What happens if a user is in the middle of filling out a long form and their token expires? How should the client handle this? (Hint: silent refresh)

3. **The Redis blacklist reintroduces shared state.** Is there a way to handle logout without any shared state? (Hint: short-lived tokens + no blacklist — accept that logged-out tokens work for up to 15 minutes)

4. **What if the JWT secret is compromised?** An attacker could forge tokens for any user with any role. How do you rotate the secret without logging out all existing users? (Hint: support multiple secrets during rotation)

5. **We store the role in the JWT.** What if you need to check permissions against a complex policy (e.g., "user X can edit document Y")? Would you put all permissions in the token, or look them up?

6. **JWTs can't be easily extended.** If you want to add a claim (e.g., `organization_id`) to existing tokens, users must log in again. How do refresh tokens help with this?

---

## Bonus Challenges

- **Add token rotation on refresh:** When a refresh token is used, invalidate it and issue a new one. This prevents replay attacks with stolen refresh tokens.

- **Implement "logout everywhere":** Store a per-user version counter in Redis. When the user clicks "logout all devices," increment the counter. The JWT filter checks if the token's version matches the current counter.

- **Add rate limiting to login:** Prevent brute-force attacks by limiting login attempts per username (e.g., 5 attempts per minute). Use Redis to track attempts.

- **Measure JWT overhead:** Run the baseline test (no auth) and the JWT test back-to-back. Compare p50 and p99 latencies. The difference is the JWT overhead per request.

---

## What's Next?

Authentication is stateless now — any server handles any user. But we still have synchronous processing bottlenecks. When a user creates a product, the API does everything in the request thread: validate, save to DB, evict caches, compute stats. If any of these steps is slow, the user waits.

Exercise 06 introduces **async processing** — offloading slow work to background queues so the API responds immediately and heavy lifting happens in the background.
