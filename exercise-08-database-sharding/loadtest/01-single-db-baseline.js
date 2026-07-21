/**
 * Exercise 08 - Test 1: Single Database Baseline
 * =================================================
 * Run with SHARDING_ENABLED=false.
 * Establishes baseline throughput and latency with a single PostgreSQL.
 * Same workload as the sharded test for fair comparison.
 *
 * Run:
 *   SHARDING_ENABLED=false docker compose up --build
 *   K6_WEB_DASHBOARD=true k6 run 01-single-db-baseline.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

const errorRate = new Rate('error_rate');
const categoryStatsLatency = new Trend('category_stats_latency_ms');
const searchLatency = new Trend('search_latency_ms');
const writeLatency = new Trend('write_latency_ms');

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
        }
    }

    // Check sharding config
    const shardRes = http.get(`${BASE_URL}/api/shard/config`);
    if (shardRes.status === 200) {
        console.log(`[Setup] Shard config: ${shardRes.body}`);
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
        // 40% — category stats (single-shard query when sharded)
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        response = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            headers: authHeaders,
        });
        categoryStatsLatency.add(response.timings.duration);

    } else if (rand < 0.65) {
        // 25% — search (cross-shard query when sharded)
        const terms = ['Premium', 'Essential', 'Professional', 'Ultimate', 'Classic'];
        const term = terms[Math.floor(Math.random() * terms.length)];
        response = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            headers: authHeaders,
        });
        searchLatency.add(response.timings.duration);

    } else if (rand < 0.85) {
        // 20% — unauthenticated get by ID (cross-shard when sharded)
        const id = Math.floor(Math.random() * 2500) + 1;
        response = http.get(`${BASE_URL}/api/products/${id}`);

    } else {
        // 15% — write (single-shard when sharded)
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        const product = {
            name: `ShardTest Product ${Date.now()}-${Math.floor(Math.random() * 10000)}`,
            description: 'Created during shard test',
            price: (Math.random() * 100 + 5).toFixed(2),
            category: cat,
            stockQuantity: Math.floor(Math.random() * 100),
        };
        response = http.post(`${BASE_URL}/api/products`, JSON.stringify(product), {
            headers: authHeaders,
        });
        writeLatency.add(response.timings.duration);
    }

    check(response, {
        'status is not 5xx': (r) => r.status < 500,
    });

    errorRate.add(response.status >= 500);
    sleep(0.05);
}
