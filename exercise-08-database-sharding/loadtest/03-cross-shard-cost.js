/**
 * Exercise 08 - Test 3: Cross-Shard vs Single-Shard Query Cost
 * ================================================================
 * Measures the latency difference between:
 *   - Single-shard queries (category stats — route to one shard)
 *   - Cross-shard queries (search — fan out to all shards)
 *
 * This test isolates the cost of cross-shard fan-out by running
 * both query types under the same load and comparing latencies.
 *
 * Expected result: cross-shard queries take roughly 2x the latency
 * of single-shard queries (2 DB round trips instead of 1).
 *
 * Run:
 *   docker compose up --build
 *   K6_WEB_DASHBOARD=true k6 run 03-cross-shard-cost.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

const errorRate = new Rate('custom_error_rate');

// Single-shard query metrics
const singleShardLatency = new Trend('single_shard_latency_ms');
const singleShardCount = new Counter('single_shard_queries');

// Cross-shard query metrics
const crossShardLatency = new Trend('cross_shard_latency_ms');
const crossShardCount = new Counter('cross_shard_queries');

// Write metrics (single-shard)
const writeLatency = new Trend('write_latency_ms');
const writeCount = new Counter('write_queries');

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
                accessToken: body.accessToken,
            });
            console.log(`[Setup] ${user.username} logged in`);
        }
    }

    // Show shard config
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
        // 40% — SINGLE-SHARD: category stats
        // Routes to exactly one shard — no fan-out
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        response = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            headers: authHeaders,
        });
        singleShardLatency.add(response.timings.duration);
        singleShardCount.add(1);

    } else if (rand < 0.70) {
        // 30% — CROSS-SHARD: search (fans out to all shards)
        const terms = ['Premium', 'Essential', 'Professional', 'Ultimate', 'Classic'];
        const term = terms[Math.floor(Math.random() * terms.length)];
        response = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            headers: authHeaders,
        });
        crossShardLatency.add(response.timings.duration);
        crossShardCount.add(1);

    } else if (rand < 0.85) {
        // 15% — CROSS-SHARD: get by ID (scatter-gather)
        const id = Math.floor(Math.random() * 2500) + 1;
        response = http.get(`${BASE_URL}/api/products/${id}`);
        crossShardLatency.add(response.timings.duration);
        crossShardCount.add(1);

    } else {
        // 15% — SINGLE-SHARD: write (routes by category)
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        const product = {
            name: `ShardCost Product ${Date.now()}-${Math.floor(Math.random() * 10000)}`,
            description: 'Created during cross-shard cost test',
            price: (Math.random() * 100 + 5).toFixed(2),
            category: cat,
            stockQuantity: Math.floor(Math.random() * 100),
        };
        response = http.post(`${BASE_URL}/api/products`, JSON.stringify(product), {
            headers: authHeaders,
        });
        writeLatency.add(response.timings.duration);
        writeCount.add(1);
    }

    errorRate.add(response.status >= 500);
    sleep(0.05);
}
