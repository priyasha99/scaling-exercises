/**
 * Test 2: WITH Redis caching enabled
 * ============================================
 * Same load profile as Test 1, but now the cache is warm.
 * Run after the app has been up for a minute (cache populated).
 *
 * What to expect:
 *   - First few requests are cache MISSes (cold cache)
 *   - After ~30 seconds, most reads are cache HITs
 *   - Dramatically lower latency and higher throughput
 *   - PostgreSQL CPU drops significantly
 *   - App server CPU drops (no computeDiscount on cache hits)
 *
 * Run:
 *   docker compose -f docker-compose.replicas.yml up --build
 *   # Wait for apps to be healthy, then:
 *   K6_WEB_DASHBOARD=true k6 run 02-with-cache.js
 *
 * Compare cache_hits vs cache_misses in the k6 output.
 * Check X-Cache-Status headers in the response.
 *
 * Usage: K6_WEB_DASHBOARD=true k6 run 02-with-cache.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const latency = new Trend('response_time_ms');
const timeouts = new Counter('timeouts');
const cacheHits = new Counter('cache_hits');
const cacheMisses = new Counter('cache_misses');

const BASE_URL = __ENV.BASE_URL || 'http://localhost';

const CATEGORIES = ['Electronics', 'Books', 'Clothing', 'Home & Kitchen',
                     'Sports', 'Toys', 'Health', 'Automotive'];

const SEARCH_TERMS = ['Premium', 'Widget', 'Smart', 'Ultra', 'Professional',
                       'Deluxe', 'Wireless', 'Portable', 'Compact', 'Advanced'];

export const options = {
    stages: [
        { duration: '10s', target: 50 },
        { duration: '20s', target: 150 },
        { duration: '20s', target: 300 },
        { duration: '30s', target: 300 },
        { duration: '20s', target: 500 },
        { duration: '20s', target: 500 },
        { duration: '15s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(50)<200', 'p(95)<2000'],
        errors: ['rate<0.02'],
    },
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
        // Same 10 search terms — high cache hit rate expected
        const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
        res = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            tags: { name: 'search' },
            timeout: '15s',
        });
    } else if (roll < 0.55) {
        // 8 categories — very high cache hit rate expected
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            tags: { name: 'stats' },
            timeout: '15s',
        });
    } else if (roll < 0.75) {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/category/${cat}`, {
            tags: { name: 'category' },
            timeout: '15s',
        });
    } else if (roll < 0.90) {
        // Product by ID — only 5000 products, so some will be repeated
        const id = Math.floor(Math.random() * 5000) + 1;
        res = http.get(`${BASE_URL}/api/products/${id}`, {
            tags: { name: 'get_by_id' },
            timeout: '15s',
        });
    } else {
        // Writes — these evict caches, causing subsequent misses
        const payload = JSON.stringify({
            name: `Cache Test Product ${Date.now()}-${Math.random()}`,
            description: 'Testing cache invalidation on writes',
            price: (Math.random() * 500 + 1).toFixed(2),
            category: CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)],
            stockQuantity: Math.floor(Math.random() * 1000),
        });
        res = http.post(`${BASE_URL}/api/products`, payload, {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'create' },
            timeout: '15s',
        });
    }

    // Track cache hit/miss from response header
    const cacheStatus = res.headers['X-Cache-Status'];
    if (cacheStatus === 'HIT') {
        cacheHits.add(1);
    } else if (cacheStatus === 'MISS') {
        cacheMisses.add(1);
    }

    const success = check(res, {
        'status is 2xx': (r) => r.status >= 200 && r.status < 300,
        'not a server error': (r) => r.status < 500,
        'response under 2s': (r) => r.timings.duration < 2000,
        'response under 5s': (r) => r.timings.duration < 5000,
    });

    errorRate.add(res.status >= 400);
    latency.add(res.timings.duration);

    if (res.timings.duration > 10000) {
        timeouts.add(1);
    }

    sleep(Math.random() * 0.5 + 0.1);
}
