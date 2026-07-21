/**
 * Exercise 06 - Test 2: Async Comparison
 * ========================================
 * Same workload as Test 1, but with ASYNC_ENABLED=true.
 * Product creation goes through RabbitMQ instead of direct DB insert.
 *
 * The API returns 202 Accepted immediately (~1ms) instead of waiting
 * for the DB insert (~50-200ms). The consumer processes messages
 * in the background.
 *
 * WHAT TO COMPARE WITH TEST 1:
 *   - write_latency_ms: should be MUCH lower (1-5ms vs 50-200ms)
 *   - req/s throughput: should be higher (API threads free faster)
 *   - error_rate: should be lower (no thread pool exhaustion)
 *   - Status polling: verify messages are actually processed
 *
 * Run:
 *   # Start with async mode (default):
 *   docker compose up --build
 *   # Then run:
 *   K6_WEB_DASHBOARD=true k6 run 02-async-comparison.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

const errorRate = new Rate('error_rate');
const writeLatency = new Trend('write_latency_ms');
const readLatency = new Trend('read_latency_ms');
const writesAccepted = new Counter('writes_accepted');
const readsCompleted = new Counter('reads_completed');
const statusPolls = new Counter('status_polls');
const statusCompleted = new Counter('status_completed');
const statusQueued = new Counter('status_queued');

const CATEGORIES = [
    'Electronics', 'Books', 'Clothing', 'Home & Kitchen',
    'Sports', 'Toys', 'Health', 'Automotive'
];

const USERS = [
    { username: 'admin', password: 'admin123' },
    { username: 'user', password: 'user123' },
    { username: 'alice', password: 'alice123' },
    { username: 'bob', password: 'bob123' },
];

export const options = {
    stages: [
        { duration: '15s', target: 250 },
        { duration: '15s', target: 500 },
        { duration: '60s', target: 500 },
        { duration: '15s', target: 0 },
    ],
    thresholds: {
        'http_req_duration': ['p(95)<15000'],
        'error_rate': ['rate<0.15'],
    },
};

export function setup() {
    const BASE_URL = 'http://localhost';
    const tokens = [];

    for (const user of USERS) {
        const res = http.post(
            `${BASE_URL}/api/auth/login`,
            JSON.stringify(user),
            { headers: { 'Content-Type': 'application/json' } }
        );

        if (res.status === 200) {
            const body = JSON.parse(res.body);
            tokens.push({
                username: user.username,
                accessToken: body.accessToken,
            });
            console.log(`[Setup] ${user.username} logged in`);
        } else {
            console.error(`[Setup] Failed to login ${user.username}: ${res.status}`);
        }
    }

    return { tokens };
}

export default function (data) {
    const BASE_URL = 'http://localhost';
    const tokenData = data.tokens[Math.floor(Math.random() * data.tokens.length)];
    const authHeaders = {
        'Authorization': `Bearer ${tokenData.accessToken}`,
        'Content-Type': 'application/json',
    };

    const rand = Math.random();
    let response;

    if (rand < 0.30) {
        // 30% writes — async: publishes to RabbitMQ, returns 202
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        const product = {
            name: `Async Product ${Date.now()}-${Math.floor(Math.random() * 10000)}`,
            description: 'Created during async comparison test',
            price: (Math.random() * 100 + 5).toFixed(2),
            category: cat,
            stockQuantity: Math.floor(Math.random() * 100),
        };
        response = http.post(`${BASE_URL}/api/products`, JSON.stringify(product), {
            headers: authHeaders,
        });

        if (response.status === 202) {
            writeLatency.add(response.timings.duration);
            writesAccepted.add(1);

            // Poll status for a subset of writes (10%)
            if (Math.random() < 0.10) {
                const body = JSON.parse(response.body);
                sleep(0.5); // Give the consumer a moment

                const statusRes = http.get(
                    `${BASE_URL}${body.statusUrl}`,
                    { headers: authHeaders }
                );
                statusPolls.add(1);

                if (statusRes.status === 200) {
                    const statusBody = JSON.parse(statusRes.body);
                    if (statusBody.status === 'COMPLETED') {
                        statusCompleted.add(1);
                    } else if (statusBody.status === 'QUEUED' || statusBody.status === 'PROCESSING') {
                        statusQueued.add(1);
                    }
                }
            }
        }
    } else if (rand < 0.55) {
        // 25% category stats
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        response = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            headers: authHeaders,
        });
        if (response.status === 200) {
            readLatency.add(response.timings.duration);
            readsCompleted.add(1);
        }
    } else if (rand < 0.75) {
        // 20% search
        const terms = ['Premium', 'Essential', 'Professional', 'Ultimate', 'Classic'];
        const term = terms[Math.floor(Math.random() * terms.length)];
        response = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            headers: authHeaders,
        });
        if (response.status === 200) {
            readLatency.add(response.timings.duration);
            readsCompleted.add(1);
        }
    } else {
        // 25% product by ID
        const id = Math.floor(Math.random() * 5000) + 1;
        response = http.get(`${BASE_URL}/api/products/${id}`, {
            headers: authHeaders,
        });
        if (response.status === 200) {
            readLatency.add(response.timings.duration);
            readsCompleted.add(1);
        }
    }

    const success = check(response, {
        'status is 2xx': (r) => r.status >= 200 && r.status < 300,
    });

    errorRate.add(!success);
    sleep(0.1);
}
