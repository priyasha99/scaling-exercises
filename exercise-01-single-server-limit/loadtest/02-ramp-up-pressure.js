/**
 * Stage 2: Ramp Up Pressure
 * ==========================
 * Gradually increase from 10 to 100 concurrent users.
 * Mix of light reads AND the heavy stats endpoint.
 * Watch response times creep up and the first errors appear.
 *
 * Usage: k6 run 02-ramp-up-pressure.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const latency = new Trend('response_time_ms');
const timeouts = new Counter('timeouts');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const CATEGORIES = ['Electronics', 'Books', 'Clothing', 'Home & Kitchen',
                     'Sports', 'Toys', 'Health', 'Automotive'];

export const options = {
    stages: [
        { duration: '15s', target: 20 },    // Warm up
        { duration: '30s', target: 50 },    // Medium pressure
        { duration: '30s', target: 100 },   // High pressure
        { duration: '30s', target: 100 },   // Sustain high pressure
        { duration: '15s', target: 0 },     // Cool down
    ],
    thresholds: {
        // NOTE: We EXPECT these to fail! That's the point of the exercise.
        http_req_duration: ['p(95)<1000'],
        errors: ['rate<0.05'],
    },
};

export default function () {
    const roll = Math.random();
    let res;

    if (roll < 0.3) {
        // 30% - Light: single product lookup
        const id = Math.floor(Math.random() * 5000) + 1;
        res = http.get(`${BASE_URL}/api/products/${id}`, {
            tags: { name: 'get_product' },
        });
    } else if (roll < 0.5) {
        // 20% - Medium: search (full table scan)
        const keywords = ['Premium', 'Wireless', 'Smart', 'Ultra', 'Eco'];
        const keyword = keywords[Math.floor(Math.random() * keywords.length)];
        res = http.get(`${BASE_URL}/api/products/search?q=${keyword}`, {
            tags: { name: 'search' },
        });
    } else if (roll < 0.7) {
        // 20% - Medium: category listing
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/category/${cat}`, {
            tags: { name: 'category' },
        });
    } else if (roll < 0.9) {
        // 20% - HEAVY: category stats (CPU-intensive)
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            tags: { name: 'stats_heavy' },
        });
    } else {
        // 10% - Write: create product
        const payload = JSON.stringify({
            name: `Load Test Product ${Date.now()}`,
            description: 'Created during load test to add write pressure',
            price: (Math.random() * 100 + 5).toFixed(2),
            category: CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)],
            stockQuantity: Math.floor(Math.random() * 200),
        });
        res = http.post(`${BASE_URL}/api/products`, payload, {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'create_product' },
        });
    }

    const success = check(res, {
        'status is 2xx': (r) => r.status >= 200 && r.status < 300,
        'response time < 2s': (r) => r.timings.duration < 2000,
        'no server error': (r) => r.status !== 500 && r.status !== 503,
    });

    errorRate.add(!success);
    latency.add(res.timings.duration);
    if (res.timings.duration > 10000) {
        timeouts.add(1);
    }

    sleep(Math.random() * 1 + 0.2); // 0.2-1.2s think time (aggressive)
}
