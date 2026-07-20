/**
 * Exercise 05 - Test 2: Authenticated load test (JWT)
 * ============================================
 * Each VU logs in once (setup phase), gets a JWT, then makes
 * authenticated requests using that token. The token works on
 * ANY server — proving stateless sessions.
 *
 * KEY OBSERVATION:
 * Watch the X-Server-Id headers. The same user's requests are
 * handled by different servers. No sticky sessions needed.
 *
 * The test includes:
 * - 70% reads (public, but with token for tracking)
 * - 20% authenticated reads (with token)
 * - 10% authenticated writes (POST, requires auth)
 *
 * Run: K6_WEB_DASHBOARD=true k6 run 02-with-cache.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter } from 'k6/metrics';

const errorRate = new Rate('error_rate');
const authSuccess = new Counter('auth_success');
const authFailure = new Counter('auth_failure');
const serverDistribution = new Counter('server_distribution');

const CATEGORIES = [
    'Electronics', 'Books', 'Clothing', 'Home & Kitchen',
    'Sports', 'Toys', 'Health', 'Automotive'
];

const SEARCH_TERMS = [
    'Premium', 'Essential', 'Professional', 'Ultimate', 'Classic',
    'Advanced', 'Deluxe', 'Basic', 'Super', 'Mega'
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

/**
 * Setup: each VU logs in and gets a JWT.
 * This happens ONCE per VU, not on every request.
 * The token is then reused for all subsequent requests.
 */
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
                refreshToken: body.refreshToken,
                serverId: body.serverId,
            });
            console.log(`[Setup] ${user.username} logged in on server: ${body.serverId}`);
        } else {
            console.error(`[Setup] Failed to login ${user.username}: ${res.status}`);
        }
    }

    return { tokens };
}

export default function (data) {
    const BASE_URL = 'http://localhost';

    // Pick a random user's token
    const tokenData = data.tokens[Math.floor(Math.random() * data.tokens.length)];
    const authHeaders = {
        'Authorization': `Bearer ${tokenData.accessToken}`,
        'Content-Type': 'application/json',
    };

    const rand = Math.random();
    let response;

    if (rand < 0.30) {
        // Category stats (public GET, but we send token to see X-Auth-User)
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        response = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            headers: authHeaders,
        });
    } else if (rand < 0.50) {
        // Search (public GET with token)
        const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
        response = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            headers: authHeaders,
        });
    } else if (rand < 0.70) {
        // Product by ID (public GET with token)
        const id = Math.floor(Math.random() * 5000) + 1;
        response = http.get(`${BASE_URL}/api/products/${id}`, {
            headers: authHeaders,
        });
    } else if (rand < 0.80) {
        // Verify token endpoint — shows token is valid on any server
        response = http.get(`${BASE_URL}/api/auth/verify`, {
            headers: authHeaders,
        });
    } else if (rand < 0.90) {
        // Category listing with token
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        response = http.get(`${BASE_URL}/api/products/category/${cat}`, {
            headers: authHeaders,
        });
    } else {
        // Authenticated write (POST — requires valid JWT)
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        const product = {
            name: `LoadTest Product ${Date.now()}`,
            description: 'Created during JWT load test',
            price: (Math.random() * 100 + 5).toFixed(2),
            category: cat,
            stockQuantity: Math.floor(Math.random() * 100),
        };
        response = http.post(`${BASE_URL}/api/products`, JSON.stringify(product), {
            headers: authHeaders,
        });
    }

    const success = check(response, {
        'status is 2xx': (r) => r.status >= 200 && r.status < 300,
        'not 401 (auth works)': (r) => r.status !== 401,
        'not 403 (role works)': (r) => r.status !== 403,
    });

    errorRate.add(!success);

    if (response.status === 401 || response.status === 403) {
        authFailure.add(1);
    } else if (response.status >= 200 && response.status < 300) {
        authSuccess.add(1);
    }

    sleep(0.1);
}
