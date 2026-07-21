/**
 * Exercise 05 - Test 3: Token refresh + logout under load
 * ============================================
 * Part C: Tests token lifecycle under heavy load.
 *
 * Each VU:
 * 1. Logs in → gets access + refresh tokens
 * 2. Makes authenticated requests
 * 3. Periodically refreshes the access token (simulating expiry)
 * 4. Occasionally logs out and re-logs in (tests blacklisting)
 *
 * KEY OBSERVATIONS:
 * - Refresh creates new access tokens without re-entering credentials
 * - Logout blacklists the old token in Redis (shared across servers)
 * - After logout, the old token is rejected by ALL servers
 * - Admin endpoints return 403 for USER role tokens
 *
 * Run: K6_WEB_DASHBOARD=true k6 run 03-cache-invalidation.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter } from 'k6/metrics';

const errorRate = new Rate('error_rate');
const tokenRefreshes = new Counter('token_refreshes');
const logouts = new Counter('logouts');
const adminAccessDenied = new Counter('admin_access_denied');
const adminAccessGranted = new Counter('admin_access_granted');

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
        { duration: '15s', target: 100 },
        { duration: '15s', target: 300 },
        { duration: '60s', target: 300 },
        { duration: '15s', target: 0 },
    ],
    thresholds: {
        'http_req_duration': ['p(95)<15000'],
        'error_rate': ['rate<0.15'],
    },
};

export default function () {
    const BASE_URL = 'http://localhost';
    const user = USERS[Math.floor(Math.random() * USERS.length)];

    // Step 1: Login
    const loginRes = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({ username: user.username, password: user.password }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    const loginOk = check(loginRes, {
        'login successful': (r) => r.status === 200,
    });

    if (!loginOk) {
        errorRate.add(true);
        sleep(1);
        return;
    }

    const loginBody = JSON.parse(loginRes.body);
    let accessToken = loginBody.accessToken;
    const refreshToken = loginBody.refreshToken;

    const authHeaders = () => ({
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
    });

    // Step 2: Make some authenticated requests
    for (let i = 0; i < 5; i++) {
        const rand = Math.random();
        let response;

        if (rand < 0.40) {
            // Read product data
            const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
            response = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
                headers: authHeaders(),
            });
        } else if (rand < 0.60) {
            // Verify token (shows it works on any server)
            response = http.get(`${BASE_URL}/api/auth/verify`, {
                headers: authHeaders(),
            });
        } else if (rand < 0.80) {
            // Try admin endpoint (should work for admin, fail for others)
            response = http.get(`${BASE_URL}/api/admin/dashboard`, {
                headers: authHeaders(),
            });

            if (response.status === 403) {
                adminAccessDenied.add(1);
            } else if (response.status === 200) {
                adminAccessGranted.add(1);
            }
        } else {
            // Create product (authenticated write)
            const product = {
                name: `Test Product ${Date.now()}`,
                description: 'Created during token lifecycle test',
                price: (Math.random() * 100 + 5).toFixed(2),
                category: CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)],
                stockQuantity: Math.floor(Math.random() * 100),
            };
            response = http.post(`${BASE_URL}/api/products`, JSON.stringify(product), {
                headers: authHeaders(),
            });
        }

        const success = check(response, {
            'request ok': (r) => r.status >= 200 && r.status < 400,
        });
        errorRate.add(!success && response.status !== 403);

        sleep(0.1);
    }

    // Step 3: Refresh the token (50% of the time)
    if (Math.random() < 0.5) {
        const refreshRes = http.post(
            `${BASE_URL}/api/auth/refresh`,
            JSON.stringify({ refreshToken: refreshToken }),
            { headers: { 'Content-Type': 'application/json' } }
        );

        const refreshOk = check(refreshRes, {
            'token refreshed': (r) => r.status === 200,
        });

        if (refreshOk) {
            const refreshBody = JSON.parse(refreshRes.body);
            accessToken = refreshBody.accessToken;
            tokenRefreshes.add(1);
        }

        // Make a request with the new token
        const verifyRes = http.get(`${BASE_URL}/api/auth/verify`, {
            headers: authHeaders(),
        });
        check(verifyRes, {
            'new token valid': (r) => r.status === 200,
        });
    }

    // Step 4: Logout (30% of the time)
    if (Math.random() < 0.3) {
        const oldToken = accessToken;

        const logoutRes = http.post(`${BASE_URL}/api/auth/logout`, null, {
            headers: authHeaders(),
        });

        check(logoutRes, {
            'logout successful': (r) => r.status === 200,
        });
        logouts.add(1);

        // Verify old token is now rejected (blacklisted)
        const reuseRes = http.get(`${BASE_URL}/api/auth/verify`, {
            headers: {
                'Authorization': `Bearer ${oldToken}`,
                'Content-Type': 'application/json',
            },
        });

        check(reuseRes, {
            'old token rejected after logout': (r) => r.status === 401,
        });
    }

    sleep(0.5);
}
