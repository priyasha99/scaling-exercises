/**
 * Exercise 05 - Test 1: Baseline (Public endpoints, no auth)
 * ============================================
 * Same as Exercise 04 — read-heavy with cache, no JWT.
 * GET endpoints are public, so this works without tokens.
 * Establishes baseline before adding auth overhead.
 *
 * Run: K6_WEB_DASHBOARD=true k6 run 01-no-cache-baseline.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter } from 'k6/metrics';

const errorRate = new Rate('error_rate');
const cacheHits = new Counter('cache_hits');
const cacheMisses = new Counter('cache_misses');

const CATEGORIES = [
    'Electronics', 'Books', 'Clothing', 'Home & Kitchen',
    'Sports', 'Toys', 'Health', 'Automotive'
];

const SEARCH_TERMS = [
    'Premium', 'Essential', 'Professional', 'Ultimate', 'Classic',
    'Advanced', 'Deluxe', 'Basic', 'Super', 'Mega'
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
        'error_rate': ['rate<0.20'],
    },
};

export default function () {
    const BASE_URL = 'http://localhost';
    const rand = Math.random();

    let response;

    if (rand < 0.25) {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        response = http.get(`${BASE_URL}/api/products/stats/${cat}`);
    } else if (rand < 0.50) {
        const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
        response = http.get(`${BASE_URL}/api/products/search?q=${term}`);
    } else if (rand < 0.75) {
        const id = Math.floor(Math.random() * 5000) + 1;
        response = http.get(`${BASE_URL}/api/products/${id}`);
    } else {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        response = http.get(`${BASE_URL}/api/products/category/${cat}`);
    }

    const success = check(response, {
        'status is 200': (r) => r.status === 200,
    });

    errorRate.add(!success);

    const cacheStatus = response.headers['X-Cache-Status'];
    if (cacheStatus === 'HIT') cacheHits.add(1);
    else if (cacheStatus === 'MISS') cacheMisses.add(1);

    sleep(0.1);
}
