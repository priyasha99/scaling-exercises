/**
 * Stage 2: Push Beyond — Find the NEW Ceiling
 * ==============================================
 * Exercise 01 broke at ~300 users. With 3 servers, can we
 * handle 1000? Let's find the new breaking point.
 *
 * This test ramps to 1000 VUs — 2x what we tried before.
 * The goal isn't to survive it perfectly — it's to see
 * WHERE the new ceiling is and what the new bottleneck becomes.
 *
 * Usage: K6_WEB_DASHBOARD=true k6 run 02-push-beyond.js
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
    stages: [
        { duration: '10s', target: 100 },     // Quick warmup
        { duration: '20s', target: 300 },     // Where Ex01 broke
        { duration: '20s', target: 500 },     // Beyond Ex01's limit
        { duration: '30s', target: 700 },     // New territory
        { duration: '30s', target: 1000 },    // The big test
        { duration: '30s', target: 1000 },    // Sustain
        { duration: '15s', target: 0 },       // Cool down
    ],
    thresholds: {
        http_req_duration: ['p(50)<1000', 'p(95)<5000'],
        errors: ['rate<0.15'],
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
            name: `Scale Test Product ${Date.now()}-${Math.random()}`,
            description: 'Created during horizontal scaling load test',
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
