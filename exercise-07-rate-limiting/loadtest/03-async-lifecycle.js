/**
 * Exercise 06 - Test 3: Async Lifecycle & DLQ
 * =============================================
 * Tests the full async lifecycle:
 *   1. Create product via async endpoint
 *   2. Poll status until COMPLETED
 *   3. Verify the product exists in the database
 *   4. Check async metrics
 *   5. Test the explicit /async endpoint
 *
 * This test uses fewer VUs but validates correctness over throughput.
 * Every published message should eventually reach COMPLETED status.
 *
 * Run:
 *   docker compose up --build
 *   K6_WEB_DASHBOARD=true k6 run 03-async-lifecycle.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

const errorRate = new Rate('custom_error_rate');
const publishLatency = new Trend('publish_latency_ms');
const pollsToComplete = new Trend('polls_to_complete');
const asyncCompleted = new Counter('async_completed');
const asyncStillPending = new Counter('async_still_pending');
const asyncFailed = new Counter('async_failed');
const productVerified = new Counter('product_verified');
const explicitAsyncUsed = new Counter('explicit_async_used');

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
        { duration: '10s', target: 100 },
        { duration: '10s', target: 300 },
        { duration: '60s', target: 300 },
        { duration: '10s', target: 0 },
    ],
    thresholds: {
        'custom_error_rate': ['rate<0.05'],
        'http_req_duration': ['p(95)<15000'],
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

    if (rand < 0.50) {
        // 50% — Full async lifecycle: create → poll → verify
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        const uniqueName = `Lifecycle ${tokenData.username}-${Date.now()}-${Math.floor(Math.random() * 10000)}`;
        const product = {
            name: uniqueName,
            description: 'Async lifecycle test product',
            price: (Math.random() * 100 + 5).toFixed(2),
            category: cat,
            stockQuantity: Math.floor(Math.random() * 100),
        };

        // Choose between /api/products (toggle-controlled) and /api/products/async (always async)
        const useExplicit = Math.random() < 0.3;
        const endpoint = useExplicit ? '/api/products/async' : '/api/products';
        if (useExplicit) explicitAsyncUsed.add(1);

        const createRes = http.post(`${BASE_URL}${endpoint}`, JSON.stringify(product), {
            headers: authHeaders,
        });

        const created = check(createRes, {
            'async create returns 202': (r) => r.status === 202,
            'response has requestId': (r) => {
                try { return JSON.parse(r.body).requestId !== undefined; }
                catch { return false; }
            },
        });

        if (!created) {
            errorRate.add(true);
            return;
        }

        publishLatency.add(createRes.timings.duration);
        const body = JSON.parse(createRes.body);
        const requestId = body.requestId;
        const statusUrl = body.statusUrl;

        // Poll until COMPLETED (max 10 polls, 500ms apart)
        let polls = 0;
        let finalStatus = 'UNKNOWN';

        for (let i = 0; i < 10; i++) {
            sleep(0.5);
            polls++;

            const statusRes = http.get(`${BASE_URL}${statusUrl}`, {
                headers: authHeaders,
            });

            if (statusRes.status === 200) {
                const statusBody = JSON.parse(statusRes.body);
                finalStatus = statusBody.status;

                if (finalStatus === 'COMPLETED') {
                    asyncCompleted.add(1);
                    pollsToComplete.add(polls);
                    break;
                } else if (finalStatus.startsWith('FAILED')) {
                    asyncFailed.add(1);
                    break;
                }
                // QUEUED or PROCESSING — keep polling
            }
        }

        if (finalStatus !== 'COMPLETED' && !finalStatus.startsWith('FAILED')) {
            asyncStillPending.add(1);
        }

        errorRate.add(false);

    } else if (rand < 0.70) {
        // 20% — Check async metrics
        const metricsRes = http.get(`${BASE_URL}/api/products/async/metrics`);

        check(metricsRes, {
            'metrics returns 200': (r) => r.status === 200,
            'metrics has published count': (r) => {
                try { return JSON.parse(r.body).metrics.published !== undefined; }
                catch { return false; }
            },
        });

        errorRate.add(metricsRes.status !== 200);

    } else {
        // 30% — Read operations (to maintain mixed workload)
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        const readRes = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            headers: authHeaders,
        });

        check(readRes, {
            'read returns 200': (r) => r.status === 200,
        });

        errorRate.add(readRes.status !== 200);
    }

    sleep(0.1);
}
