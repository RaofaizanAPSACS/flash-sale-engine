import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// =============================================================================
// Flash Sale Engine v2 - k6 Load Test
//
// Tests the Redis-optimized purchase flow:
//   Rate Limit (429) -> Redis Stock Check (409) -> Redis Stream (202) -> DB (async)
//
// Usage:
//   k6 run k6-v2-load-test.js
//   k6 run --out web-dashboard k6-v2-load-test.js
//   k6 run -e RUN_500K_REQUESTS=1 k6-v2-load-test.js  # target ~0.5M total requests
//   k6 run -e RUN_1M_REQUESTS=1 k6-v2-load-test.js   # target ~1M total requests
//
// Override stock:  k6 run -e EXPECTED_STOCK=1000 k6-v2-load-test.js
//
// --- High load on one machine ---
// - 0.5M total requests: RUN_500K_REQUESTS=1 (hold 35s at 10k VUs; total ~500k with ramp).
// - 1M total requests: RUN_1M_REQUESTS=1 (~3m at 10k VUs).
// - 1M concurrent VUs: NOT feasible on one machine (use k6 Cloud or distributed injectors).
// =============================================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v2';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';
const EXPECTED_STOCK = parseInt(__ENV.EXPECTED_STOCK || '10000');

// Custom metrics
const purchaseAccepted = new Rate('purchase_accepted');       // 202 responses
const purchaseSoldOut = new Rate('purchase_sold_out');         // 409 responses
const purchaseRateLimited = new Rate('purchase_rate_limited'); // 429 responses
const purchaseError = new Rate('purchase_error');              // 500+ responses
const purchaseTimeout = new Rate('purchase_timeout');          // Timeouts (status 0)

const purchaseLatency = new Trend('purchase_latency');
const totalAccepted = new Counter('total_accepted');
const totalSoldOut = new Counter('total_sold_out');
const totalRateLimited = new Counter('total_rate_limited');

// Load profile: 10k VUs. RUN_500K_REQUESTS=1 => ~500k total; RUN_1M_REQUESTS=1 => ~1M total.
// Calibrated from: 715k requests in 130s with 1m hold (ramp 50s + hold 60s + ramp 20s).
const run500K = __ENV.RUN_500K_REQUESTS === '1' || __ENV.RUN_500K_REQUESTS === 'true';
const run1M = __ENV.RUN_1M_REQUESTS === '1' || __ENV.RUN_1M_REQUESTS === 'true';
const peakVUs = 10000;
const holdDuration = run1M ? '3m' : (run500K ? '35s' : '1m');  // 35s => ~500k total, 1m => ~700k, 3m => ~2.1M

export const options = {
    scenarios: {
        flash_sale: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 1000 },
                { duration: '20s', target: 5000 },
                { duration: '20s', target: peakVUs },
                { duration: holdDuration, target: peakVUs },
                { duration: '10s', target: 5000 },
                { duration: '10s', target: 0 },
            ],
            gracefulRampDown: '10s',
            gracefulStop: '30s',
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<10000'],             // 95% of requests < 10s
        http_req_failed: ['rate<0.999'],                // Allow high "failure" (409 counted as failure by k6)
        'purchase_error': ['rate<0.10'],                // Less than 10% real errors
        'purchase_timeout': ['rate<0.10'],              // Less than 10% timeouts
    },
};

export default function () {
    const userId = `k6_user_${__VU}_${__ITER}`;

    const payload = JSON.stringify({ userId: userId });
    const params = {
        headers: {
            'Content-Type': 'application/json',
            'X-User-Id': userId,
        },
        timeout: '10s',
    };

    const res = http.post(
        `${BASE_URL}/products/${PRODUCT_ID}/purchase`,
        payload,
        params
    );

    purchaseLatency.add(res.timings.duration);

    if (res.status === 202) {
        purchaseAccepted.add(true);
        purchaseSoldOut.add(false);
        purchaseRateLimited.add(false);
        purchaseError.add(false);
        purchaseTimeout.add(false);
        totalAccepted.add(1);

        check(res, {
            'purchase accepted': (r) => r.status === 202,
            'has orderId': (r) => JSON.parse(r.body).orderId !== undefined,
        });

    } else if (res.status === 409) {
        purchaseAccepted.add(false);
        purchaseSoldOut.add(true);
        purchaseRateLimited.add(false);
        purchaseError.add(false);
        purchaseTimeout.add(false);
        totalSoldOut.add(1);

    } else if (res.status === 429) {
        purchaseAccepted.add(false);
        purchaseSoldOut.add(false);
        purchaseRateLimited.add(true);
        purchaseError.add(false);
        purchaseTimeout.add(false);
        totalRateLimited.add(1);

    } else if (res.status === 0) {
        purchaseAccepted.add(false);
        purchaseSoldOut.add(false);
        purchaseRateLimited.add(false);
        purchaseError.add(true);
        purchaseTimeout.add(true);
        sleep(0.5);

    } else {
        purchaseAccepted.add(false);
        purchaseSoldOut.add(false);
        purchaseRateLimited.add(false);
        purchaseError.add(true);
        purchaseTimeout.add(false);
    }

    sleep(0.05);
}

// After the test, verify correctness
export function handleSummary(data) {
    const accepted = data.metrics.total_accepted ? data.metrics.total_accepted.values.count : 0;
    const soldOut = data.metrics.total_sold_out ? data.metrics.total_sold_out.values.count : 0;
    const rateLimited = data.metrics.total_rate_limited ? data.metrics.total_rate_limited.values.count : 0;
    const totalRequests = accepted + soldOut + rateLimited;

    // Get latency from http_req_duration
    // k6 default percentile keys: avg, min, med (=p50), max, p(90), p(95)
    // Note: p(99) is not a default key; we use p(90) instead
    const duration = data.metrics.http_req_duration || {};
    const vals = duration.values || {};
    const avg = vals['avg'] || 0;
    const p50 = vals['med'] || 0;           // k6 uses 'med' for median/P50
    const p90 = vals['p(90)'] || 0;
    const p95 = vals['p(95)'] || 0;

    const oversold = accepted > EXPECTED_STOCK;

    const summary = `
==========================================================
  Flash Sale v2 - k6 Load Test Results
==========================================================

  Total Requests     : ${totalRequests}
  Purchases Accepted : ${accepted} (expected: ${EXPECTED_STOCK})
  Sold Out (409)     : ${soldOut}
  Rate Limited (429) : ${rateLimited}

  Response Times:
    Avg : ${avg.toFixed(1)}ms
    P50 : ${p50.toFixed(1)}ms
    P90 : ${p90.toFixed(1)}ms
    P95 : ${p95.toFixed(1)}ms

  Correctness:
    Oversold : ${oversold ? 'YES' : 'NO'}
    Verdict  : ${oversold ? 'FAIL - Overselling detected!' : 'CORRECT - No overselling'}
==========================================================
`;

    console.log(summary);
    return { stdout: summary };
}
