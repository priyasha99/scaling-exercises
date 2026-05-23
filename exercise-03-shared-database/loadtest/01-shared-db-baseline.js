/**
 * Test 1: Shared Database Baseline
 * ============================================
 * Light load to verify the shared database works correctly.
 * Creates products and reads them back — they should always
 * be visible because all servers share one PostgreSQL.
 *
 * In Exercise 02, creating on Server 1 and reading from Server 2
 * would return different results (each had their own H2 DB).
 * Now it should be consistent.
 *
 * Also establishes baseline performance metrics for comparison
 * against Exercise 02's in-memory H2 results. PostgreSQL on disk
 * will be slower even under light load — that's expected.
 *
 * Usage: K6_WEB_DASHBOARD=true k6 run 01-shared-db-baseline.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const latency = new Trend('response_time_ms');
const timeouts = new Counter('timeouts');
const dataConsistency = new Rate('data_consistent');

const BASE_URL = __ENV.BASE_URL || 'http://localhost';

const CATEGORIES = ['Electronics', 'Books', 'Clothing', 'Home & Kitchen',
                     'Sports', 'Toys', 'Health', 'Automotive'];

const SEARCH_TERMS = ['Premium', 'Widget', 'Smart', 'Ultra', 'Professional'];

export const options = {
    // Light load — just 50 VUs
    stages: [
        { duration: '10s', target: 20 },   // Warm up
        { duration: '30s', target: 50 },   // Steady state
        { duration: '20s', target: 50 },   // Hold
        { duration: '10s', target: 0 },    // Cool down
    ],
    thresholds: {
        http_req_duration: ['p(50)<500', 'p(95)<2000'],
        errors: ['rate<0.01'],
    },
};

export default function () {
    const roll = Math.random();
    let res;

    if (roll < 0.10) {
        // Health check — verify server ID changes (load balancing works)
        res = http.get(`${BASE_URL}/api/products/health`, {
            tags: { name: 'health' },
            timeout: '15s',
        });
    } else if (roll < 0.25) {
        // Search (LIKE query — slower on PostgreSQL vs H2)
        const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
        res = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            tags: { name: 'search' },
            timeout: '15s',
        });
    } else if (roll < 0.40) {
        // Stats (CPU + DB heavy)
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            tags: { name: 'stats' },
            timeout: '15s',
        });
    } else if (roll < 0.60) {
        // Category list
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/category/${cat}`, {
            tags: { name: 'category' },
            timeout: '15s',
        });
    } else if (roll < 0.80) {
        // Read all products
        res = http.get(`${BASE_URL}/api/products`, {
            tags: { name: 'list_all' },
            timeout: '15s',
        });
    } else {
        // Create + verify consistency
        // Create a product, then immediately read it back.
        // This tests that the shared DB is working correctly.
        const uniqueName = `Consistency-Test-${Date.now()}-${Math.random().toString(36).substring(7)}`;
        const payload = JSON.stringify({
            name: uniqueName,
            description: 'Testing shared database consistency',
            price: (Math.random() * 100 + 1).toFixed(2),
            category: CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)],
            stockQuantity: Math.floor(Math.random() * 100),
        });

        const createRes = http.post(`${BASE_URL}/api/products`, payload, {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'create_product' },
            timeout: '15s',
        });

        if (createRes.status === 200 || createRes.status === 201) {
            const created = JSON.parse(createRes.body);
            // Read back from (potentially) a different server
            const readRes = http.get(`${BASE_URL}/api/products/${created.id}`, {
                tags: { name: 'read_back' },
                timeout: '15s',
            });
            // Check that the product exists on whatever server handled the read
            const consistent = readRes.status === 200;
            dataConsistency.add(consistent);
            if (!consistent) {
                console.log(`INCONSISTENCY: Created product ${created.id} on one server, got ${readRes.status} reading back`);
            }
        }

        res = createRes;
    }

    if (res) {
        check(res, {
            'status is 2xx': (r) => r.status >= 200 && r.status < 300,
            'response under 2s': (r) => r.timings.duration < 2000,
        });

        errorRate.add(res.status >= 400);
        latency.add(res.timings.duration);

        if (res.timings.duration > 10000) {
            timeouts.add(1);
        }
    }

    sleep(Math.random() * 0.5 + 0.2);
}
