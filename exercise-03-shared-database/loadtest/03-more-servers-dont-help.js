/**
 * Test 3: More App Servers Don't Help
 * ============================================
 * Holds 300 VUs steady for 3 minutes.
 * Run this test, then scale app servers while it runs:
 *
 *   # Start with 3 servers (default)
 *   # Run the test, note throughput
 *
 *   # Scale to 6 servers
 *   docker compose up --scale app=6 --no-recreate -d && \
 *   sleep 35 && \
 *   docker compose exec nginx nginx -s reload
 *
 *   # Watch the k6 dashboard — does throughput improve?
 *   # Spoiler: barely. The database is the ceiling.
 *
 * This is the KEY INSIGHT of Exercise 03:
 * In Exercise 02, adding servers gave near-linear throughput gains
 * because each server had its own database. Now they share one
 * PostgreSQL, so adding app servers just adds more consumers
 * fighting for the same limited resource.
 *
 * Think of it like a restaurant: adding more waiters (app servers)
 * doesn't help if the kitchen (database) can only cook so fast.
 *
 * Usage: K6_WEB_DASHBOARD=true k6 run 03-more-servers-dont-help.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const latency = new Trend('response_time_ms');
const timeouts = new Counter('timeouts');
const serverErrors = new Counter('server_errors_5xx');

const BASE_URL = __ENV.BASE_URL || 'http://localhost';

const CATEGORIES = ['Electronics', 'Books', 'Clothing', 'Home & Kitchen',
                     'Sports', 'Toys', 'Health', 'Automotive'];

const SEARCH_TERMS = ['Premium', 'Widget', 'Smart', 'Ultra', 'Professional',
                       'Deluxe', 'Wireless', 'Portable', 'Compact', 'Advanced'];

export const options = {
    // Steady load for 3 minutes — gives time to scale during the test
    stages: [
        { duration: '15s', target: 300 },   // Ramp up
        { duration: '180s', target: 300 },   // Hold steady — scale during this
        { duration: '15s', target: 0 },      // Cool down
    ],
};

export default function () {
    const roll = Math.random();
    let res;

    if (roll < 0.10) {
        res = http.get(`${BASE_URL}/api/products/health`, {
            tags: { name: 'health' },
            timeout: '15s',
        });
    } else if (roll < 0.30) {
        const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
        res = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            tags: { name: 'search' },
            timeout: '15s',
        });
    } else if (roll < 0.55) {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            tags: { name: 'stats_heavy' },
            timeout: '15s',
        });
    } else if (roll < 0.75) {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/category/${cat}`, {
            tags: { name: 'category' },
            timeout: '15s',
        });
    } else if (roll < 0.90) {
        res = http.get(`${BASE_URL}/api/products`, {
            tags: { name: 'list_all' },
            timeout: '15s',
        });
    } else {
        const payload = JSON.stringify({
            name: `Scale Test Product ${Date.now()}-${Math.random()}`,
            description: 'Testing whether adding app servers improves throughput',
            price: (Math.random() * 500 + 1).toFixed(2),
            category: CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)],
            stockQuantity: Math.floor(Math.random() * 1000),
        });
        res = http.post(`${BASE_URL}/api/products`, payload, {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'create_product' },
            timeout: '15s',
        });
    }

    check(res, {
        'status is 2xx': (r) => r.status >= 200 && r.status < 300,
        'not a server error': (r) => r.status < 500,
        'response under 2s': (r) => r.timings.duration < 2000,
    });

    errorRate.add(res.status >= 400);
    latency.add(res.timings.duration);

    if (res.status >= 500) {
        serverErrors.add(1);
    }
    if (res.timings.duration > 10000) {
        timeouts.add(1);
    }

    sleep(Math.random() * 0.5 + 0.1);
}
