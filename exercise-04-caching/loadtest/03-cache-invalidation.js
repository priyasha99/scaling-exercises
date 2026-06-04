/**
 * Test 3: Cache Invalidation Under Write-Heavy Load
 * ============================================
 * Increases write percentage to 30% (vs 10% in Test 2).
 * Each write evicts cached entries, causing more cache misses.
 *
 * This demonstrates the cache invalidation trade-off:
 *   - More writes → more evictions → lower hit rate
 *   - The cache is less effective for write-heavy workloads
 *   - But still better than no cache at all
 *
 * What to watch:
 *   - cache_hits vs cache_misses ratio
 *   - Compare latency to Test 2 (read-heavy with cache)
 *   - PostgreSQL CPU should be higher than Test 2 (more misses)
 *   - Redis CPU stays low (it's just key lookups)
 *
 * Run:
 *   docker compose -f docker-compose.replicas.yml exec redis redis-cli FLUSHALL
 *   K6_WEB_DASHBOARD=true k6 run 03-cache-invalidation.js
 *
 * Usage: K6_WEB_DASHBOARD=true k6 run 03-cache-invalidation.js
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
        http_req_duration: ['p(50)<1000', 'p(95)<5000'],
        errors: ['rate<0.10'],
    },
};

export default function () {
    const roll = Math.random();
    let res;

    if (roll < 0.05) {
        res = http.get(`${BASE_URL}/api/products/health`, {
            tags: { name: 'health' },
            timeout: '15s',
        });
    } else if (roll < 0.20) {
        const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
        res = http.get(`${BASE_URL}/api/products/search?q=${term}`, {
            tags: { name: 'search' },
            timeout: '15s',
        });
    } else if (roll < 0.35) {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/stats/${cat}`, {
            tags: { name: 'stats' },
            timeout: '15s',
        });
    } else if (roll < 0.50) {
        const cat = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        res = http.get(`${BASE_URL}/api/products/category/${cat}`, {
            tags: { name: 'category' },
            timeout: '15s',
        });
    } else if (roll < 0.70) {
        const id = Math.floor(Math.random() * 5000) + 1;
        res = http.get(`${BASE_URL}/api/products/${id}`, {
            tags: { name: 'get_by_id' },
            timeout: '15s',
        });
    } else {
        // 30% WRITES — 3x more than Test 2
        // Each write evicts: all_products, search (all), plus
        // the specific category's products_by_category and category_stats
        const payload = JSON.stringify({
            name: `Write-Heavy Test ${Date.now()}-${Math.random()}`,
            description: 'Testing cache invalidation with frequent writes',
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

    // Track cache hit/miss
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
