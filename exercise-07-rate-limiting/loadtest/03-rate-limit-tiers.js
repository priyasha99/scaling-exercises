/**
 * Exercise 07 - Test 3: Rate Limit Tiers and Backpressure
 * =========================================================
 * Tests each rate limit tier independently:
 *   - Per-user: each user has their own bucket
 *   - Per-IP: unauthenticated requests limited by IP
 *   - Admin vs User: ADMIN gets higher limits
 *   - Global: system-wide backpressure
 *
 * Uses fewer VUs but validates that limits work correctly.
 *
 * Run:
 *   docker compose up --build
 *   K6_WEB_DASHBOARD=true k6 run 03-rate-limit-tiers.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter } from 'k6/metrics';

const errorRate = new Rate('custom_error_rate');
const userLimited = new Counter('user_rate_limited');
const adminLimited = new Counter('admin_rate_limited');
const ipLimited = new Counter('ip_rate_limited');
const userAllowed = new Counter('user_allowed');
const adminAllowed = new Counter('admin_allowed');
const ipAllowed = new Counter('ip_allowed');
const retryAfterRespected = new Counter('retry_after_respected');

const CATEGORIES = [
    'Electronics', 'Books', 'Clothing', 'Home & Kitchen',
    'Sports', 'Toys', 'Health', 'Automotive'
];

const USERS = [
    { username: 'admin', password: 'admin123', role: 'ADMIN' },
    { username: 'user', password: 'user123', role: 'USER' },
    { username: 'alice', password: 'alice123', role: 'USER' },
    { username: 'bob', password: 'bob123', role: 'USER' },
];

export const options = {
    stages: [
        { duration: '10s', target: 50 },
        { duration: '10s', target: 150 },
        { duration: '60s', target: 150 },
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
                role: user.role,
                accessToken: body.accessToken,
            });
            console.log(`[Setup] ${user.username} (${user.role}) logged in`);
        } else {
            console.error(`[Setup] Failed to login ${user.username}: ${res.status}`);
        }
    }

    return { tokens };
}

export default function (data) {
    const BASE_URL = 'http://localhost';

    const rand = Math.random();

    if (rand < 0.35) {
        // 35% — Regular USER requests (should hit per-user limit at ~10 req/s)
        const userTokens = data.tokens.filter(t => t.role === 'USER');
        const tokenData = userTokens[Math.floor(Math.random() * userTokens.length)];
        const authHeaders = {
            'Authorization': `Bearer ${tokenData.accessToken}`,
            'Content-Type': 'application/json',
        };

        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        const response = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            headers: authHeaders,
        });

        if (response.status === 429) {
            userLimited.add(1);
        } else if (response.status >= 200 && response.status < 300) {
            userAllowed.add(1);
        }

        errorRate.add(response.status >= 500);

    } else if (rand < 0.55) {
        // 20% — ADMIN requests (should hit limit much later — 50 req/s)
        const adminTokens = data.tokens.filter(t => t.role === 'ADMIN');
        const tokenData = adminTokens[0];
        const authHeaders = {
            'Authorization': `Bearer ${tokenData.accessToken}`,
            'Content-Type': 'application/json',
        };

        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        const response = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            headers: authHeaders,
        });

        if (response.status === 429) {
            adminLimited.add(1);
        } else if (response.status >= 200 && response.status < 300) {
            adminAllowed.add(1);
        }

        errorRate.add(response.status >= 500);

    } else if (rand < 0.75) {
        // 20% — Unauthenticated (per-IP, strictest limit — 5 req/s)
        const id = Math.floor(Math.random() * 5000) + 1;
        const response = http.get(`${BASE_URL}/api/products/${id}`);

        if (response.status === 429) {
            ipLimited.add(1);
        } else if (response.status >= 200 && response.status < 300) {
            ipAllowed.add(1);
        }

        errorRate.add(response.status >= 500);

    } else {
        // 25% — Retry-After test: get 429, wait, try again
        const tokenData = data.tokens[Math.floor(Math.random() * data.tokens.length)];
        const authHeaders = {
            'Authorization': `Bearer ${tokenData.accessToken}`,
            'Content-Type': 'application/json',
        };

        // Rapid-fire 3 requests to likely trigger a 429
        let lastResponse;
        for (let i = 0; i < 3; i++) {
            const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
            lastResponse = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
                headers: authHeaders,
            });
            if (lastResponse.status === 429) break;
        }

        if (lastResponse.status === 429) {
            const retryAfter = lastResponse.headers['Retry-After'];
            if (retryAfter) {
                // Wait the Retry-After time (capped at 3s for test speed)
                const waitTime = Math.min(parseInt(retryAfter), 3);
                sleep(waitTime);

                // Try again — should succeed now
                const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
                const retryRes = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
                    headers: authHeaders,
                });

                if (retryRes.status >= 200 && retryRes.status < 300) {
                    retryAfterRespected.add(1);
                }
            }
        }

        errorRate.add(lastResponse.status >= 500);
    }

    sleep(0.05);
}
