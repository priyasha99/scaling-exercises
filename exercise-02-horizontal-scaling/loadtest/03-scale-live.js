/**
 * Stage 3: Scale While Under Load
 * =================================
 * This is a LONG-RUNNING test (3 minutes). While it's running,
 * you'll scale the number of app instances up and down using
 * docker compose commands in another terminal.
 *
 * The test holds steady at 500 VUs the entire time, giving you
 * a stable baseline to see the IMMEDIATE effect of adding or
 * removing servers.
 *
 * Instructions:
 *   Terminal 1: K6_WEB_DASHBOARD=true k6 run 03-scale-live.js
 *   Terminal 2: (run these commands at different points during the test)
 *
 *     # Start with 3 servers (default). Watch the dashboard.
 *     # After 30s, scale down to 1:
 *     docker compose up --scale app=1 --no-recreate -d
 *
 *     # Watch metrics degrade! After 30s, scale up to 5:
 *     docker compose up --scale app=5 --no-recreate -d
 *
 *     # Watch metrics recover! After 30s, go to 8:
 *     docker compose up --scale app=8 --no-recreate -d
 *
 *     # Diminishing returns? Compare 5 vs 8 servers.
 *
 * Watch the k6 web dashboard — you'll see response times and
 * error rates change in real time as servers come and go.
 *
 * Usage: K6_WEB_DASHBOARD=true k6 run 03-scale-live.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const latency = new Trend('response_time_ms');
const serverErrors = new Counter('server_errors_5xx');

const BASE_URL = __ENV.BASE_URL || 'http://localhost';

const CATEGORIES = ['Electronics', 'Books', 'Clothing', 'Home & Kitchen',
                     'Sports', 'Toys', 'Health', 'Automotive'];

const SEARCH_TERMS = ['Premium', 'Widget', 'Smart', 'Ultra', 'Professional',
                       'Deluxe', 'Wireless', 'Portable', 'Compact', 'Advanced'];

export const options = {
    // Ramp up to 500, hold steady for 3 minutes
    stages: [
        { duration: '20s', target: 500 },     // Ramp up quickly
        { duration: '180s', target: 500 },    // Hold steady — you'll scale during this window
        { duration: '15s', target: 0 },       // Cool down
    ],
    thresholds: {
        http_req_duration: ['p(95)<5000'],
        errors: ['rate<0.20'],
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
            name: `Live Scale Test ${Date.now()}-${Math.random()}`,
            description: 'Created during live scaling test',
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
    });

    errorRate.add(res.status >= 400);
    latency.add(res.timings.duration);

    if (res.status >= 500) {
        serverErrors.add(1);
    }

    sleep(Math.random() * 0.5 + 0.1);
}
