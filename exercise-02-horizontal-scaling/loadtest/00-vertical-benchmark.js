/**
 * Vertical Scaling Benchmark
 * ============================
 * Run this SAME test against each vertical tier (small, medium,
 * large, xlarge) to get an apples-to-apples comparison.
 *
 * The test ramps to 300 VUs (the point where Exercise 01 broke)
 * and holds for 60 seconds. Compare the results across tiers.
 *
 * Usage (run against each tier separately):
 *   # Start SMALL tier:
 *   docker compose -f docker-compose.small.yml up --build
 *   K6_WEB_DASHBOARD=true k6 run 00-vertical-benchmark.js
 *   # Record results, then: docker compose -f docker-compose.small.yml down
 *
 *   # Start MEDIUM tier:
 *   docker compose -f docker-compose.medium.yml up --build
 *   K6_WEB_DASHBOARD=true k6 run 00-vertical-benchmark.js
 *   # Record results, then: docker compose -f docker-compose.medium.yml down
 *
 *   # Repeat for large and xlarge...
 *
 * NOTE: This test hits port 8080 (direct to app), NOT port 80 (Nginx).
 * Vertical scaling doesn't use a load balancer — it's one bigger server.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const latency = new Trend('response_time_ms');
const timeouts = new Counter('timeouts');
const serverErrors = new Counter('server_errors_5xx');

// Port 8080 = direct to app (no load balancer for vertical scaling)
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const CATEGORIES = ['Electronics', 'Books', 'Clothing', 'Home & Kitchen',
                     'Sports', 'Toys', 'Health', 'Automotive'];

const SEARCH_TERMS = ['Premium', 'Widget', 'Smart', 'Ultra', 'Professional',
                       'Deluxe', 'Wireless', 'Portable', 'Compact', 'Advanced'];

export const options = {
    stages: [
        { duration: '10s', target: 50 },      // Warmup
        { duration: '15s', target: 150 },     // Ramp
        { duration: '15s', target: 300 },     // Where Ex01 broke
        { duration: '60s', target: 300 },     // Hold — collect steady-state data
        { duration: '10s', target: 0 },       // Cool down
    ],
    thresholds: {
        http_req_duration: ['p(50)<500', 'p(95)<3000'],
        errors: ['rate<0.10'],
    },
};

export default function () {
    const roll = Math.random();
    let res;

    if (roll < 0.15) {
        res = http.get(`${BASE_URL}/api/products/health`, {
            tags: { name: 'health' },
            timeout: '15s',
        });
    } else if (roll < 0.30) {
        const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
        res = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            tags: { name: 'search' },
            timeout: '15s',
        });
    } else if (roll < 0.55) {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            tags: { name: 'stats_heavy' },
            timeout: '15s',
        });
    } else if (roll < 0.75) {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/category/${cat}`, {
            tags: { name: 'category' },
            timeout: '15s',
        });
    } else {
        const payload = JSON.stringify({
            name: `Vertical Benchmark ${Date.now()}-${Math.random()}`,
            description: 'Created during vertical scaling benchmark',
            price: (Math.random() * 500 + 1).toFixed(2),
            category: CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)],
            stockQuantity: Math.floor(Math.random() * 1000),
        });
        res = http.post(`${BASE_URL}/api/products`, payload, {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'create_product' },
            timeout: '15s',
        });
    }

    const success = check(res, {
        'status is 2xx': (r) => r.status >= 200 && r.status < 300,
        'not a server error': (r) => r.status < 500,
        'response under 2s': (r) => r.timings.duration < 2000,
        'response under 5s': (r) => r.timings.duration < 5000,
    });

    errorRate.add(res.status >= 400);
    latency.add(res.timings.duration);

    if (res.status >= 500) {
        serverErrors.add(1);
    }
    if (res.timings.duration > 10000) {
        timeouts.add(1);
    }

    sleep(Math.random() * 0.5 + 0.1);
}
