/**
 * Stage 3: Break the Server
 * ==========================
 * This is the crescendo. Ramp to 300+ concurrent users,
 * hammering the heaviest endpoints. The server WILL buckle.
 *
 * Watch for:
 *   - Response times spiking from ms to seconds
 *   - Error rates climbing above 5%, 10%, 20%+
 *   - Health endpoint itself becoming slow (everything is saturated)
 *   - Possible OOM kill if memory pressure is high enough
 *
 * Usage: k6 run 03-break-the-server.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const latency = new Trend('response_time_ms');
const timeouts = new Counter('timeouts');
const serverErrors = new Counter('server_errors_5xx');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const CATEGORIES = ['Electronics', 'Books', 'Clothing', 'Home & Kitchen',
                     'Sports', 'Toys', 'Health', 'Automotive'];

const SEARCH_TERMS = ['Premium', 'Widget', 'Smart', 'Ultra', 'Professional',
                       'Deluxe', 'Wireless', 'Portable', 'Compact', 'Advanced'];

export const options = {
    stages: [
        { duration: '10s', target: 50 },     // Quick warmup
        { duration: '20s', target: 150 },    // Ramp to heavy load
        { duration: '20s', target: 300 },    // Push beyond limits
        { duration: '30s', target: 300 },    // SUSTAIN the pain
        { duration: '20s', target: 500 },    // Overkill - watch it crumble
        { duration: '20s', target: 500 },    // Keep the pressure
        { duration: '15s', target: 0 },      // Release
    ],
    // These thresholds WILL fail. We keep them to show in the report.
    thresholds: {
        http_req_duration: ['p(50)<500', 'p(95)<2000', 'p(99)<5000'],
        errors: ['rate<0.10'],
    },
};

export default function () {
    const roll = Math.random();
    let res;

    if (roll < 0.15) {
        // 15% - Health check (to show even this degrades)
        res = http.get(`${BASE_URL}/api/products/health`, {
            tags: { name: 'health' },
            timeout: '15s',
        });
    } else if (roll < 0.30) {
        // 15% - Search (table scans under massive concurrency)
        const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
        res = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            tags: { name: 'search' },
            timeout: '15s',
        });
    } else if (roll < 0.60) {
        // 30% - Stats (the CPU killer)
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            tags: { name: 'stats_heavy' },
            timeout: '15s',
        });
    } else if (roll < 0.80) {
        // 20% - Category listing
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/category/${cat}`, {
            tags: { name: 'category' },
            timeout: '15s',
        });
    } else {
        // 20% - Writes (DB contention)
        const payload = JSON.stringify({
            name: `Stress Test Product ${Date.now()}-${Math.random()}`,
            description: 'High-concurrency write pressure test product with extra description to add payload size',
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

    // Minimal think time - we want maximum pressure
    sleep(Math.random() * 0.5 + 0.1);
}
