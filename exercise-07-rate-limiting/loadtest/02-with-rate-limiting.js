/**
 * Exercise 07 - Test 2: With Rate Limiting Enabled
 * ==================================================
 * Same workload as Test 1, but with rate limiting ON.
 *
 * WHAT TO COMPARE:
 *   - 429 count: how many requests were rate limited
 *   - Throughput: should be capped by the global limit
 *   - Latency: should be similar or better (less load on backend)
 *   - X-RateLimit-Remaining headers: tokens counting down
 *
 * With 300 VUs, 4 users, and per-user limit of 10 req/s:
 *   4 users × 10 req/s = 40 req/s per-user throughput
 *   Global limit: 200 req/s
 *   Expected: significant 429s because VUs exceed per-user limits
 *
 * Run:
 *   docker compose up --build
 *   K6_WEB_DASHBOARD=true k6 run 02-with-rate-limiting.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

const errorRate = new Rate('server_error_rate');
const rateLimited = new Counter('rate_limited_429');
const rateLimitedByUser = new Counter('rate_limited_user');
const rateLimitedByIp = new Counter('rate_limited_ip');
const rateLimitedByGlobal = new Counter('rate_limited_global');
const successfulRequests = new Counter('successful_requests');
const retryAfterValues = new Trend('retry_after_seconds');

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

    // Check rate limit config
    const configRes = http.get(`${BASE_URL}/api/rate-limit/config`);
    if (configRes.status === 200) {
        console.log(`[Setup] Rate limit config: ${configRes.body}`);
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

    if (rand < 0.40) {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        response = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            headers: authHeaders,
        });
    } else if (rand < 0.70) {
        const terms = ['Premium', 'Essential', 'Professional', 'Ultimate', 'Classic'];
        const term = terms[Math.floor(Math.random() * terms.length)];
        response = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            headers: authHeaders,
        });
    } else if (rand < 0.85) {
        // Unauthenticated — will hit per-IP rate limit
        const id = Math.floor(Math.random() * 5000) + 1;
        response = http.get(`${BASE_URL}/api/products/${id}`);
    } else {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        const product = {
            name: `RateTest Product ${Date.now()}-${Math.floor(Math.random() * 10000)}`,
            description: 'Created during rate limit test',
            price: (Math.random() * 100 + 5).toFixed(2),
            category: cat,
            stockQuantity: Math.floor(Math.random() * 100),
        };
        response = http.post(`${BASE_URL}/api/products`, JSON.stringify(product), {
            headers: authHeaders,
        });
    }

    if (response.status === 429) {
        rateLimited.add(1);

        // Track which tier caused the rejection
        const retryAfter = response.headers['Retry-After'];
        if (retryAfter) {
            retryAfterValues.add(parseInt(retryAfter));
        }

        try {
            const body = JSON.parse(response.body);
            if (body.tier === 'user') rateLimitedByUser.add(1);
            else if (body.tier === 'ip') rateLimitedByIp.add(1);
            else if (body.tier === 'global') rateLimitedByGlobal.add(1);
        } catch (e) {}
    } else if (response.status >= 200 && response.status < 300) {
        successfulRequests.add(1);
    }

    // Check rate limit headers on successful responses
    check(response, {
        'has rate limit headers': (r) => r.status === 429 || r.headers['X-RateLimit-Limit'] !== undefined,
    });

    errorRate.add(response.status >= 500);
    sleep(0.05);
}
