/**
 * Stage 1: Same Load That Broke Exercise 01
 * ============================================
 * This is IDENTICAL to Exercise 01's 03-break-the-server.js
 * but pointed at port 80 (Nginx) instead of 8080 (direct app).
 *
 * In Exercise 01, this load destroyed the server:
 *   - Median: 7,462ms
 *   - p95: 14,630ms
 *   - Timeouts: 1,631
 *   - Only 16% of requests under 2s
 *
 * Now run it against 3 servers behind a load balancer.
 * Compare the numbers. That's the whole exercise.
 *
 * Usage: K6_WEB_DASHBOARD=true k6 run 01-same-load-more-servers.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const latency = new Trend('response_time_ms');
const timeouts = new Counter('timeouts');
const serverErrors = new Counter('server_errors_5xx');

// Port 80 = Nginx load balancer (NOT 8080 direct to app)
const BASE_URL = __ENV.BASE_URL || 'http://localhost';

const CATEGORIES = ['Electronics', 'Books', 'Clothing', 'Home & Kitchen',
                     'Sports', 'Toys', 'Health', 'Automotive'];

const SEARCH_TERMS = ['Premium', 'Widget', 'Smart', 'Ultra', 'Professional',
                       'Deluxe', 'Wireless', 'Portable', 'Compact', 'Advanced'];

export const options = {
    // SAME stages as Exercise 01's break-the-server test
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
        // These thresholds FAILED in Exercise 01. Will they pass now?
        http_req_duration: ['p(50)<500', 'p(95)<2000', 'p(99)<5000'],
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
    } else if (roll < 0.60) {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            tags: { name: 'stats_heavy' },
            timeout: '15s',
        });
    } else if (roll < 0.80) {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/category/${cat}`, {
            tags: { name: 'category' },
            timeout: '15s',
        });
    } else {
        const payload = JSON.stringify({
            name: `Stress Test Product ${Date.now()}-${Math.random()}`,
            description: 'High-concurrency write pressure test product',
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
