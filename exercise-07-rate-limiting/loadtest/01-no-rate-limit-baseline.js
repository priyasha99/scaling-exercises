/**
 * Exercise 07 - Test 1: No Rate Limiting Baseline
 * =================================================
 * Run with RATE_LIMIT_ENABLED=false.
 * Establishes baseline throughput and latency without rate limiting.
 * Same workload as the rate-limited test so the comparison is fair.
 *
 * Run:
 *   RATE_LIMIT_ENABLED=false docker compose up --build
 *   K6_WEB_DASHBOARD=true k6 run 01-no-rate-limit-baseline.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter } from 'k6/metrics';

const errorRate = new Rate('error_rate');
const rateLimited = new Counter('rate_limited_429');

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

    if (rand < 0.40) {
        // 40% authenticated reads (category stats — expensive)
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        response = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            headers: authHeaders,
        });
    } else if (rand < 0.70) {
        // 30% authenticated reads (search)
        const terms = ['Premium', 'Essential', 'Professional', 'Ultimate', 'Classic'];
        const term = terms[Math.floor(Math.random() * terms.length)];
        response = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            headers: authHeaders,
        });
    } else if (rand < 0.85) {
        // 15% unauthenticated reads (public endpoint — per-IP limiting)
        const id = Math.floor(Math.random() * 5000) + 1;
        response = http.get(`${BASE_URL}/api/products/${id}`);
    } else {
        // 15% authenticated writes
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
    }

    const success = check(response, {
        'status is not 5xx': (r) => r.status < 500,
    });

    errorRate.add(response.status >= 500);
    sleep(0.05);
}
