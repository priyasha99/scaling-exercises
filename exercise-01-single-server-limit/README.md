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

### Step 3: Run the load tests (in order!)

Open a **new terminal** and run each stage. After each one, look at the results before moving on.

#### Stage 1: Gentle Warmup (baseline)
```bash
cd exercise-01-single-server-limit/loadtest
k6 run 01-gentle-warmup.js
```

**What to observe:**
- All checks should PASS (green)
- p95 response time should be well under 500ms
- Error rate should be 0%
- This is your baseline — "normal" looks like this

#### Stage 2: Ramp Up Pressure
```bash
k6 run 02-ramp-up-pressure.js
```

**What to observe:**
- Watch response times creep up as users increase
- Around 50-80 users, you'll see latency spikes
- First 5xx errors may appear
- Some checks will start FAILING (red)
- In the Docker terminal, you might see log output slow down

#### Stage 3: Break the Server
```bash
k6 run 03-break-the-server.js
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

**Key metrics to compare across stages:**

| Metric            | Stage 1 (10 users) | Stage 2 (100 users) | Stage 3 (500 users) |
|-------------------|---------------------|----------------------|----------------------|
| p50 latency       | ~20ms               | ~200ms               | ~3000ms+             |
| p95 latency       | ~100ms              | ~2000ms              | ~10000ms+            |
| Error rate        | 0%                  | ~5%                  | ~20%+                |
| Requests/sec      | ~50                 | ~150 (plateau)       | ~100 (DECLINING!)    |

**The key insight:** Notice that requests/sec actually DROPS at Stage 3. Adding more users doesn't mean more throughput — past a point, the server spends all its time context-switching, garbage collecting, and managing queues instead of doing useful work.

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

- **Watch container metrics** while the load test runs:
  ```bash
  docker stats
  ```
  When does CPU hit 100%? When does memory spike?

- **Add `-e JAVA_OPTS="-Xmx128m -Xms64m"` to docker-compose.yml** to cut memory in half. How much sooner does the server break?

---

## Cleanup

```bash
docker compose down
```

---

## What's Next?

Now that you've seen a single server fail, Exercise 02 will show how horizontal scaling (multiple servers behind a load balancer) pushes this ceiling higher — and introduces a new set of problems.
