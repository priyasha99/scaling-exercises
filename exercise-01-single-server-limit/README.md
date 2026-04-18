# Exercise 01: Single Server Breaking Point

**Goal:** Experience firsthand why a single server can't handle unlimited traffic. You'll run a Spring Boot app in a resource-constrained Docker container and ramp up concurrent users until it breaks.

**What you'll learn:**
- How thread pools, connection pools, and memory limits create hard ceilings
- What degradation looks like (latency creep → errors → total failure)
- How to read load test results and identify bottleneck types
- Why this motivates everything else in the scaling journey

---

## Prerequisites Setup

### 1. Install Docker Desktop

**macOS:**
```bash
# Option A: Download from https://www.docker.com/products/docker-desktop/
# Option B: Homebrew
brew install --cask docker
```

**Windows:**
```bash
# Download from https://www.docker.com/products/docker-desktop/
# Requires WSL2 - Docker Desktop installer will guide you through it
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get update
sudo apt-get install docker.io docker-compose-v2
sudo usermod -aG docker $USER
# Log out and back in for group change to take effect
```

After installing, verify Docker is running:
```bash
docker --version
docker compose version
```

### 2. Install k6 (Load Testing Tool)

**macOS:**
```bash
brew install k6
```

**Windows:**
```bash
# Download MSI installer from https://dl.k6.io/msi/k6-latest-amd64.msi
# Or use Chocolatey:
choco install k6
```

**Linux:**
```bash
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D68
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update
sudo apt-get install k6
```

Verify:
```bash
k6 version
```

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                Docker Container                       │
│    ┌─────────────────────────────────────────────┐   │
│    │          Spring Boot App                     │   │
│    │                                              │   │
│    │   Tomcat (50 threads max)                    │   │
│    │      │                                       │   │
│    │      ▼                                       │   │
│    │   ProductController ──► ProductService        │   │
│    │      │                     │ (CPU-heavy       │   │
│    │      │                     │  computations)   │   │
│    │      ▼                     ▼                  │   │
│    │   HikariCP (10 connections max)               │   │
│    │      │                                       │   │
│    │      ▼                                       │   │
│    │   H2 Database (in-memory)                    │   │
│    │   [5000 products seeded]                     │   │
│    └─────────────────────────────────────────────┘   │
│                                                       │
│    Resource Limits: 1 CPU, 512MB RAM, 256MB JVM heap │
└──────────────────────────────────────────────────────┘
```

**Where are the bottlenecks?**

| Resource              | Limit | What happens when exhausted                         |
|-----------------------|-------|-----------------------------------------------------|
| Tomcat threads        | 50    | New requests queue, then get rejected                |
| DB connection pool    | 10    | Threads block waiting for a connection               |
| JVM heap              | 256MB | GC thrashing → long pauses → OOM kill                |
| CPU                   | 1 core| All computation shares one core → everything slows   |
| Docker memory         | 512MB | Container OOM killed by Docker                       |

---

## Running the Exercise

### Step 1: Start the server

```bash
cd exercise-01-single-server-limit
docker compose up --build
```

Wait until you see:
```
Seeded 5000 products into the database.
Started ScalingExerciseApplication in X seconds
```

**Leave this terminal open.** You'll watch the logs here while running load tests in another terminal.

### Step 2: Verify it's running

```bash
curl http://localhost:8080/api/products/health
```

You should see JSON with status, memory info, and processor count.

### Step 3: Monitor the server (keep this running!)

Open a **second terminal** and run:
```bash
docker stats
```

This gives you a live-updating table showing CPU %, memory usage, memory limit, and network I/O for your container. It refreshes every second. Keep this visible — you'll watch CPU climb from ~5% to 100% as load increases.

```
CONTAINER ID   NAME                          CPU %   MEM USAGE / LIMIT   NET I/O
a1b2c3d4e5f6   exercise-01-single-server..   4.32%   198MiB / 512MiB     1.2MB / 850kB
```

### Step 4: Run the load tests (in order!)

Open a **third terminal** and run each stage. Use the k6 web dashboard for real-time visibility — it opens automatically at `http://localhost:5665` and shows VU count, request rates, response times, and error rates updating live.

After each stage, compare the k6 dashboard with the `docker stats` output. The cause and effect becomes impossible to miss.

#### Stage 1: Gentle Warmup (baseline)
```bash
cd exercise-01-single-server-limit/loadtest
K6_WEB_DASHBOARD=true k6 run 01-gentle-warmup.js
```

**What to observe:**
- All checks should PASS (green)
- p95 response time should be well under 500ms
- Error rate should be 0%
- This is your baseline — "normal" looks like this

#### Stage 2: Ramp Up Pressure
```bash
K6_WEB_DASHBOARD=true k6 run 02-ramp-up-pressure.js
```

**What to observe:**
- Watch response times creep up as users increase
- Around 50-80 users, you'll see latency spikes
- First 5xx errors may appear
- Some checks will start FAILING (red)
- In the Docker terminal, you might see log output slow down

#### Stage 3: Break the Server
```bash
K6_WEB_DASHBOARD=true k6 run 03-break-the-server.js
```

**What to observe:**
- Error rates will climb above 10%, 20%, maybe higher
- p95 response times will be measured in SECONDS, not milliseconds
- Even the health endpoint (which does no real work) will be slow
- The server might OOM and restart (Docker will show this)
- Timeouts everywhere

---

## Understanding the Results

After each k6 run, you'll see a summary like:

```
http_req_duration ... avg=342ms  min=5ms  med=120ms  max=12500ms  p(90)=890ms  p(95)=2100ms
http_req_failed ..... 23.45%
iterations .......... 8432
```

**Key metrics to compare across stages (actual results):**

| Metric            | Stage 1 (10 users) | Stage 2 (100 users)  | Stage 3 (500 users)     |
|-------------------|---------------------|----------------------|-------------------------|
| Median response   | 13ms                | 44ms                 | **7,462ms**             |
| p90 latency       | 49ms                | 1,840ms              | **13,403ms**            |
| p95 latency       | 68ms                | 2,509ms              | **14,630ms**            |
| Max response      | 438ms               | 6,628ms              | **15,009ms** (timeout!) |
| Error rate        | 0%                  | 0.14%                | **5.31%**               |
| Timeouts          | 0                   | 0                    | **1,631**               |
| Requests/sec      | 3.8                 | 46.8                 | **36.6 (DECLINING!)**   |
| "Under 2s"        | 100%                | 91%                  | **16%**                 |

**Key insights:**

- **Throughput collapses.** Requests/sec goes DOWN from Stage 2 to Stage 3. Adding more users actually made the server do *less* work, not more. The CPU spends all its time context-switching and garbage collecting instead of processing requests.
- **Averages lie.** In Stage 2, the average is 583ms but the median is 44ms — a 13x gap. Most users are fine, but an unlucky group waits 6+ seconds. This is why production systems monitor percentiles (p50, p90, p99), not averages.
- **One bad endpoint poisons everything.** The heavy `/stats` endpoint consumed all threads and DB connections, starving even lightweight health checks. One expensive query can take down an entire server.

---

## Discussion Questions

After running all three stages, discuss with your team:

1. **Where did you see the first signs of trouble?** Was it latency, errors, or throughput? Why does latency degrade before errors appear?

2. **Look at the health endpoint response times in Stage 3.** It does almost zero work, yet it's slow. Why? (Hint: thread pool starvation)

3. **What if we just increased the thread pool to 500?** Would that fix it? (Hint: what would that do to memory and CPU contention?)

4. **The server has 50 Tomcat threads but only 10 DB connections.** When 50 threads all try to query the DB simultaneously, what happens? This is an example of a ______ (resource bottleneck / cascading failure).

5. **If you needed to handle 10x the traffic right now with zero code changes, what would you do?** (This leads into Exercise 02: Horizontal Scaling)

---

## Bonus Challenges

If you finish early:

- **Modify `application.properties`** to increase the thread pool to 200 and the DB pool to 50. Re-run Stage 3. Does it help? By how much? Is there a new bottleneck?

- **Watch container metrics** while the load test runs (if you haven't already):
  ```bash
  docker stats
  ```
  When does CPU hit 100%? When does memory spike? Compare this across all 3 stages.

- **Add `-e JAVA_OPTS="-Xmx128m -Xms64m"` to docker-compose.yml** to cut memory in half. How much sooner does the server break?

---

## Monitoring Deep Dive

While the load tests run, you can query Spring Boot Actuator endpoints to see what's happening inside the JVM:

```bash
# JVM memory usage
curl -s http://localhost:8080/actuator/metrics/jvm.memory.used | python3 -m json.tool

# Live thread count
curl -s http://localhost:8080/actuator/metrics/jvm.threads.live | python3 -m json.tool

# Tomcat threads currently busy (compare this to the 50 thread max)
curl -s http://localhost:8080/actuator/metrics/tomcat.threads.busy | python3 -m json.tool

# HikariCP active DB connections (compare this to the 10 connection max)
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | python3 -m json.tool

# HikariCP pending threads waiting for a connection
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.pending | python3 -m json.tool
```

Try querying these during Stage 1 vs Stage 3 — during Stage 3, you'll see Tomcat threads pinned at 50 and HikariCP connections at 10 with many pending. That's the bottleneck chain in action.

---

## Cleanup

```bash
docker compose down
```

---

## What's Next?

Now that you've seen a single server fail, Exercise 02 will show how horizontal scaling (multiple servers behind a load balancer) pushes this ceiling higher — and introduces a new set of problems.
