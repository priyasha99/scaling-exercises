/**
 * Stage 1: Gentle Warmup
 * =======================
 * 10 virtual users hitting the lightest endpoints.
 * Everything should be fast and happy here.
 * Run this first to establish a BASELINE of "normal" performance.
 *
 * Usage: k6 run 01-gentle-warmup.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const latency = new Trend('response_time_ms');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    stages: [
        { duration: '10s', target: 5 },   // Ramp up to 5 users
        { duration: '30s', target: 10 },   // Hold at 10 users
        { duration: '10s', target: 0 },    // Ramp down
    ],
    thresholds: {
        http_req_duration: ['p(95)<500'],   // 95% of requests under 500ms
        errors: ['rate<0.01'],              // Less than 1% errors
    },
};

export default function () {
    // Mix of light requests
    const requests = [
        { name: 'health',      url: `${BASE_URL}/api/products/health` },
        { name: 'get_product',  url: `${BASE_URL}/api/products/1` },
        { name: 'list_all',     url: `${BASE_URL}/api/products` },
    ];

    const req = requests[Math.floor(Math.random() * requests.length)];
    const res = http.get(req.url, { tags: { name: req.name } });

    check(res, {
        'status is 200': (r) => r.status === 200,
        'response time < 500ms': (r) => r.timings.duration < 500,
    });

    errorRate.add(res.status !== 200);
    latency.add(res.timings.duration);

    sleep(Math.random() * 2 + 0.5); // 0.5-2.5s think time
}
