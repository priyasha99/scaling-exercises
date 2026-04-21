# Exercise 02: Vertical Scaling vs Horizontal Scaling

**Goal:** Explore the two fundamental ways to handle more traffic — making one server bigger (vertical) vs adding more servers (horizontal). You'll see why vertical scaling hits a ceiling and horizontal scaling is the path forward.

**What you'll learn:**
- Vertical scaling improves things but with diminishing returns and hard limits
- Horizontal scaling gives near-linear throughput gains with the same hardware budget
- How a load balancer distributes traffic across servers
- How to scale instances live and see the effect in real time
- What new problems horizontal scaling introduces

**Prerequisites:** Complete Exercise 01 first. You need to have seen the single server break.

---

## Part A: Vertical Scaling (Bigger Box)

The intuitive first reaction when a server struggles: "Just give it more CPU and RAM." Let's test that.

### Architecture

```
Client ──► [ App Server ] ──► DB
              ▲
              │
         Make this bigger:
         SMALL  → 0.5 CPU,  256MB
         MEDIUM → 1 CPU,    512MB   (same as Exercise 01)
         LARGE  → 2 CPUs,   1GB
         XLARGE → 4 CPUs,   2GB
```

Same app, same code. We just give the Docker container more resources each time and see how much it helps.

### Running the Vertical Scaling Tests

Run the SAME load test against each tier. Record the results after each run so you can compare.

**Important:** Stop the previous tier before starting the next one — they all use port 8080.

#### Tier 1: SMALL (0.5 CPU, 256MB)

```bash
cd exercise-02-horizontal-scaling

# Terminal 1: Start the server
docker compose -f docker-compose.small.yml up --build

# Terminal 2: Monitor
docker stats

# Terminal 3: Run the benchmark
cd loadtest
K6_WEB_DASHBOARD=true k6 run 00-vertical-benchmark.js

# When done, stop it:
docker compose -f docker-compose.small.yml down
```

#### Tier 2: MEDIUM (1 CPU, 512MB)

```bash
docker compose -f docker-compose.medium.yml up --build
# (Run the same benchmark in another terminal)
K6_WEB_DASHBOARD=true k6 run 00-vertical-benchmark.js
docker compose -f docker-compose.medium.yml down
```

#### Tier 3: LARGE (2 CPUs, 1GB)

```bash
docker compose -f docker-compose.large.yml up --build
K6_WEB_DASHBOARD=true k6 run 00-vertical-benchmark.js
docker compose -f docker-compose.large.yml down
```

#### Tier 4: XLARGE (4 CPUs, 2GB)

```bash
docker compose -f docker-compose.xlarge.yml up --build
K6_WEB_DASHBOARD=true k6 run 00-vertical-benchmark.js
docker compose -f docker-compose.xlarge.yml down
```

### What to Record

After each run, fill in this table with your results:

| Metric | SMALL (0.5 CPU, 256MB) | MEDIUM (1 CPU, 512MB) | LARGE (2 CPU, 1GB) | XLARGE (4 CPU, 2GB) |
|---|---|---|---|---|
| Median response | | | | |
| p95 | | | | |
| Error rate | | | | |
| Requests/sec | | | | |
| Timeouts | | | | |

### What to Observe

- **SMALL → MEDIUM:** Big improvement. Doubling resources roughly doubles capacity. This feels great.
- **MEDIUM → LARGE:** Good improvement, but probably not 2x. More like 1.5x. Diminishing returns start.
- **LARGE → XLARGE:** Some improvement, but you're paying 2x more for much less than 2x gain. The app's thread model, GC pauses, and lock contention become the bottleneck — not raw CPU/RAM.

**The key insight:** Vertical scaling has an asymptotic ceiling. No matter how big you make the box, a single JVM with a single thread pool and a single connection pool can only do so much. And in the real world, there's a literal hardware limit — you can't buy a server with 1000 CPUs.

**The cost argument:** If SMALL costs $X/month, XLARGE costs ~8X/month (4x CPU, 8x RAM). But it doesn't deliver 8x the performance. This is the economic argument for horizontal scaling.

---

## Part B: Horizontal Scaling (More Boxes)

Instead of one big server, let's run three small ones behind a load balancer.

### Architecture

```
                        ┌──► [ App Server 1 ] ──► DB (in-memory)
                        │      (1 CPU, 512MB)
Client ──► [ Nginx ] ──┼──► [ App Server 2 ] ──► DB (in-memory)
            (LB)        │      (1 CPU, 512MB)
                        └──► [ App Server 3 ] ──► DB (in-memory)
                                (1 CPU, 512MB)
```

Each app server is identical to Exercise 01's MEDIUM tier — same Docker image, same resource limits, same 50 threads and 10 DB connections. Nginx distributes requests across them using round-robin (Request 1 → Server 1, Request 2 → Server 2, Request 3 → Server 3, Request 4 → Server 1...).

**Total resources:** 3 CPUs and 1.5GB RAM — less than the XLARGE tier (4 CPUs, 2GB). Let's see how it compares.

### Running the Horizontal Scaling Tests

#### Step 1: Start the cluster

```bash
cd exercise-02-horizontal-scaling
docker compose up --build
```

Wait for all three app instances to report "Started ScalingExerciseApplication." This takes longer than a single server since three JVMs start up.

#### Step 2: Monitor all containers

```bash
docker stats
```

You'll see four containers: one Nginx and three app instances. Watch how CPU spreads across them.

#### Step 3: Verify Nginx is routing

```bash
# Hit the load balancer (port 80, not 8080)
curl http://localhost/api/products/health

# Run it a few times — each response comes from a different container
curl http://localhost/api/products/health
curl http://localhost/api/products/health
```

#### Test 1: Same Load That Broke Exercise 01

Replay the exact load that destroyed the single server.

```bash
cd loadtest
K6_WEB_DASHBOARD=true k6 run 01-same-load-more-servers.js
```

**Compare against your Exercise 01 Stage 3 results:**

| Metric | Exercise 01 (1 server) | 3 servers + LB |
|---|---|---|
| Median | 7,462ms | ??? |
| p90 | 13,403ms | ??? |
| p95 | 14,630ms | ??? |
| Errors | 5.31% | ??? |
| Timeouts | 1,631 | ??? |
| Requests/sec | 36.6 | ??? |
| Under 2s | 16% | ??? |

#### Test 2: Push Beyond — Find the New Ceiling

3 servers survived 500 users. How about 1000?

```bash
K6_WEB_DASHBOARD=true k6 run 02-push-beyond.js
```

**What to observe:**
- At what VU count do response times start climbing?
- Check `docker stats` — are all 3 containers equally loaded?
- If it breaks, where is the new bottleneck?

#### Test 3: Scale While Under Load (the fun one!)

This test holds steady at 500 VUs for 3 minutes. While it runs, scale servers up and down and watch the k6 web dashboard (`http://localhost:5665`) react in real time.

```bash
# Terminal 1: Start the load test
K6_WEB_DASHBOARD=true k6 run 03-scale-live.js

# Terminal 2: Run these commands ~30 seconds apart

# Scale DOWN to 1 server — watch metrics degrade
docker compose up --scale app=1 --no-recreate -d

# Scale UP to 5 servers — watch metrics recover
docker compose up --scale app=5 --no-recreate -d

# Scale to 8 servers — diminishing returns?
docker compose up --scale app=8 --no-recreate -d
```

**What to watch on the dashboard:**
- Scaling to 1: response times spike, errors appear (back to Exercise 01!)
- Scaling to 5: response times drop, errors clear
- Scaling to 8: does it improve much over 5? (Probably not — at some point your laptop's physical CPU is the limit)

---

## Part C: The Comparison

After running both parts, fill in the final comparison:

| Config | CPUs | RAM | Median | p95 | Req/sec | Errors | Cost analogy |
|---|---|---|---|---|---|---|---|
| SMALL (vertical) | 0.5 | 256MB | | | | | $25/mo |
| MEDIUM (vertical) | 1 | 512MB | | | | | $50/mo |
| LARGE (vertical) | 2 | 1GB | | | | | $100/mo |
| XLARGE (vertical) | 4 | 2GB | | | | | $200/mo |
| 3x MEDIUM (horizontal) | 3 | 1.5GB | | | | | $150/mo + LB |

The 3x MEDIUM horizontal setup uses FEWER total resources than XLARGE but likely performs BETTER. That's the economic and technical argument for horizontal scaling.

---

## Discussion Questions

1. **Did XLARGE (4 CPU, 2GB) outperform 3x MEDIUM (3 CPU, 1.5GB)?** If not, why? What makes three separate JVMs more efficient than one big one?

2. **During Test 3, when you scaled from 1 to 5 servers, how quickly did performance improve?** Was it instant, or did it take a few seconds? What was happening during that transition? (Hint: JVM startup time, health check intervals)

3. **Each app server has its own in-memory H2 database.** A product created on Server 1 doesn't exist on Server 2. What happens if a user creates a product and immediately reads the list? How would you fix this? (This leads to Exercise 03)

4. **Round-robin sends equal requests to each server regardless of how busy it is.** If Server 1 is processing three heavy `/stats` requests and Server 2 just finished a `/health` check, round-robin doesn't care. What strategy would be smarter? (Look up `least_conn` in the Nginx docs)

5. **What if Nginx itself crashes?** All traffic stops, even though the app servers are fine. How is this solved in production? (Hint: redundant load balancers, DNS failover)

6. **Can you keep adding servers forever?** What eventually becomes the bottleneck? (Hint: the shared database — which doesn't exist yet in this exercise, but will in Exercise 03)

---

## Bonus Challenges

- **Try `least_conn` load balancing:** Edit `nginx/nginx.conf`, add `least_conn;` inside the `upstream` block, restart with `docker compose restart nginx`, and re-run a load test. Compare results to round-robin.

- **Check Nginx logs for distribution:**
  ```bash
  docker compose logs nginx | grep "upstream:" | head -20
  ```

- **Compare cost efficiency:** Your vertical XLARGE uses 4 CPUs + 2GB = ~$200/mo equivalent. Three MEDIUM instances use 3 CPUs + 1.5GB = ~$150/mo + ~$20/mo for a load balancer. Which performs better for less money?

---

## Cleanup

```bash
# Stop horizontal setup
docker compose down

# Or stop any vertical tier
docker compose -f docker-compose.small.yml down
```

---

## What's Next?

Horizontal scaling solved the CPU/thread/memory bottleneck, but introduced new problems:
- Each server has its own database (no shared state)
- No shared session storage
- The load balancer is a single point of failure

Exercise 03 tackles the database problem: a shared database that becomes the NEXT bottleneck, and how read replicas help.
