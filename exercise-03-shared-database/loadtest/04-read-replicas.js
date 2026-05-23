/**
 * Test 4: Read Replicas Performance
 * ============================================
 * Same load profile as Test 2 (02-db-bottleneck.js).
 * Run this against the replicas setup:
 *
 *   docker compose -f docker-compose.replicas.yml up --build
 *   K6_WEB_DASHBOARD=true k6 run 04-read-replicas.js
 *
 * Compare results against Test 2 (single PostgreSQL):
 *   - Read-heavy queries (search, stats, category, list) now
 *     route to replicas, leaving the primary free for writes
 *   - The primary handles only ~10% of requests (creates)
 *   - Read throughput should improve significantly
 *   - Write latency should also improve (less contention on primary)
 *
 * Watch `docker stats`:
 *   - Primary PostgreSQL: low CPU (mostly writes + WAL streaming)
 *   - Replica PostgreSQL: high CPU (handling all the reads)
 *   - App servers: moderate CPU (same as Test 2)
 *
 * Usage: K6_WEB_DASHBOARD=true k6 run 04-read-replicas.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const latency = new Trend('response_time_ms');
const timeouts = new Counter('timeouts');
const serverErrors = new Counter('server_errors_5xx');

const BASE_URL = __ENV.BASE_URL || 'http://localhost';

const CATEGORIES = ['Electronics', 'Books', 'Clothing', 'Home & Kitchen',
                     'Sports', 'Toys', 'Health', 'Automotive'];

const SEARCH_TERMS = ['Premium', 'Widget', 'Smart', 'Ultra', 'Professional',
                       'Deluxe', 'Wireless', 'Portable', 'Compact', 'Advanced'];

export const options = {
    // Same ramp as Test 2 for fair comparison
    stages: [
        { duration: '10s', target: 50 },
        { duration: '20s', target: 150 },
        { duration: '20s', target: 300 },
        { duration: '30s', target: 300 },
        { duration: '20s', target: 500 },
        { duration: '20s', target: 500 },
        { duration: '15s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(50)<500', 'p(95)<3000'],
        errors: ['rate<0.05'],
    },
};

export default function () {
    const roll = Math.random();
    let res;

    if (roll < 0.10) {
        res = http.get(`${BASE_URL}/api/products/health`, {
            tags: { name: 'health' },
            timeout: '15s',
        });
    } else if (roll < 0.30) {
        // Search → replica (readOnly=true)
        const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
        res = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            tags: { name: 'search_replica' },
            timeout: '15s',
        });
    } else if (roll < 0.55) {
        // Stats → replica (readOnly=true)
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            tags: { name: 'stats_replica' },
            timeout: '15s',
        });
    } else if (roll < 0.75) {
        // Category → replica (readOnly=true)
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/category/${cat}`, {
            tags: { name: 'category_replica' },
            timeout: '15s',
        });
    } else if (roll < 0.90) {
        // List all → replica (readOnly=true)
        res = http.get(`${BASE_URL}/api/products`, {
            tags: { name: 'list_all_replica' },
            timeout: '15s',
        });
    } else {
        // Create → primary (write transaction)
        const payload = JSON.stringify({
            name: `Replica Test Product ${Date.now()}-${Math.random()}`,
            description: 'Write goes to primary, reads go to replica',
            price: (Math.random() * 500 + 1).toFixed(2),
            category: CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)],
            stockQuantity: Math.floor(Math.random() * 1000),
        });
        res = http.post(`${BASE_URL}/api/products`, payload, {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'create_primary' },
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
