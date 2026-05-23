/**
 * Test 2: Database Bottleneck
 * ============================================
 * Ramp to 500 VUs against 3 servers + shared PostgreSQL.
 * Same load profile as Exercise 02 Test 1.
 *
 * In Exercise 02 with H2 in-memory, this load was handled well:
 *   - Median: 248ms
 *   - Errors: 0.03%
 *   - Throughput: 166.7 req/s
 *
 * Now with shared PostgreSQL, watch what happens:
 *   - The LIKE search query is much slower on disk
 *   - All 3 servers' DB connections compete for PostgreSQL
 *   - Connection pool wait times appear in the metrics
 *   - PostgreSQL CPU/memory becomes the ceiling
 *
 * Watch `docker stats` — you'll see PostgreSQL's CPU pinned
 * while app servers have capacity to spare. The bottleneck
 * has moved from "app servers" to "database."
 *
 * Usage: K6_WEB_DASHBOARD=true k6 run 02-db-bottleneck.js
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
    // Same ramp as Exercise 02 Test 1
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
        http_req_duration: ['p(50)<1000', 'p(95)<5000'],
        errors: ['rate<0.10'],
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
        // Search — this is the DB killer on PostgreSQL.
        // LIKE '%keyword%' forces a sequential scan on every row.
        // H2 did this in memory. PostgreSQL reads from disk.
        const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
        res = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            tags: { name: 'search' },
            timeout: '15s',
        });
    } else if (roll < 0.55) {
        // Stats — CPU heavy + DB read. Burns both resources.
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
    } else if (roll < 0.90) {
        res = http.get(`${BASE_URL}/api/products`, {
            tags: { name: 'list_all' },
            timeout: '15s',
        });
    } else {
        // Writes — compete for PostgreSQL row locks + WAL
        const payload = JSON.stringify({
            name: `Load Test Product ${Date.now()}-${Math.random()}`,
            description: 'Write pressure test product for shared database',
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
