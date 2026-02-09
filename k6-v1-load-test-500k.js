import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// =============================================================================
// Flash Sale Engine v1 - k6 Load Test (500K target, comparable to v2)
//
// v1 API: POST /api/v1/products/{id}/purchase
// - 200: Purchase successful (v2 uses 202)
// - 409: Sold out
// - 503/504/500: Service/timeout/server error (no rate limiting in v1)
//
// Same load profile as v2 500K for direct comparison:
//   10k peak VUs, 35s hold at peak, 10s timeout, 0.05s sleep.
// =============================================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';
const EXPECTED_STOCK = parseInt(__ENV.EXPECTED_STOCK || '10000');

// Custom metrics (same names as v2 for comparison)
const purchaseAccepted = new Rate('purchase_accepted');       // 200 in v1
const purchaseSoldOut = new Rate('purchase_sold_out');        // 409
const purchaseRateLimited = new Rate('purchase_rate_limited'); // 0 in v1 (no rate limit)
const purchaseError = new Rate('purchase_error');              // 503, 504, 500
const purchaseTimeout = new Rate('purchase_timeout');         // status 0

const purchaseLatency = new Trend('purchase_latency');
const totalAccepted = new Counter('total_accepted');
const totalSoldOut = new Counter('total_sold_out');
const totalRateLimited = new Counter('total_rate_limited');
const totalErrors = new Counter('total_errors');
const totalTimeouts = new Counter('total_timeouts');

// Same load profile as v2 500K: 10k VUs, 35s hold at peak
const peakVUs = 10000;
const holdDuration = '35s';

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
        http_req_duration: ['p(95)<10000'],
        http_req_failed: ['rate<0.999'],
        'purchase_error': ['rate<0.10'],
        'purchase_timeout': ['rate<0.10'],
    },
};

export default function () {
    const userId = `k6_user_${__VU}_${__ITER}`;

    const payload = JSON.stringify({ userId: userId });
    const params = {
        headers: { 'Content-Type': 'application/json' },
        timeout: '10s',
    };

    const res = http.post(
        `${BASE_URL}/products/${PRODUCT_ID}/purchase`,
        payload,
        params
    );

    purchaseLatency.add(res.timings.duration);

    // Parse response body to verify actual success (v1 returns 200 even for some errors)
    let responseBody = null;
    let isActuallySuccess = false;
    if (res.status === 200) {
        try {
            responseBody = JSON.parse(res.body);
            isActuallySuccess = responseBody.success === true && responseBody.orderId !== undefined;
        } catch (e) {
            // Invalid JSON - treat as error
            isActuallySuccess = false;
        }
    }

    if (res.status === 200 && isActuallySuccess) {
        purchaseAccepted.add(true);
        purchaseSoldOut.add(false);
        purchaseRateLimited.add(false);
        purchaseError.add(false);
        purchaseTimeout.add(false);
        totalAccepted.add(1);
        check(res, {
            'purchase successful': (r) => r.status === 200 && isActuallySuccess,
            'has orderId': (r) => responseBody && responseBody.orderId !== undefined,
        });
    } else if (res.status === 200 && !isActuallySuccess) {
        // Status 200 but success=false (shouldn't happen, but handle it)
        purchaseAccepted.add(false);
        purchaseSoldOut.add(false);
        purchaseRateLimited.add(false);
        purchaseError.add(true);
        purchaseTimeout.add(false);
        totalErrors.add(1);
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
        totalTimeouts.add(1);
        totalErrors.add(1);
        sleep(0.5);
    } else {
        purchaseAccepted.add(false);
        purchaseSoldOut.add(false);
        purchaseRateLimited.add(false);
        purchaseError.add(true);
        purchaseTimeout.add(false);
        totalErrors.add(1);
    }

    sleep(0.05);
}

export function handleSummary(data) {
    const accepted = data.metrics.total_accepted ? data.metrics.total_accepted.values.count : 0;
    const soldOut = data.metrics.total_sold_out ? data.metrics.total_sold_out.values.count : 0;
    const rateLimited = data.metrics.total_rate_limited ? data.metrics.total_rate_limited.values.count : 0;
    const errors = data.metrics.total_errors ? data.metrics.total_errors.values.count : 0;
    const timeouts = data.metrics.total_timeouts ? data.metrics.total_timeouts.values.count : 0;
    // Use k6's http_reqs count for accurate total (includes all requests: success, errors, timeouts)
    const totalRequests = data.metrics.http_reqs ? data.metrics.http_reqs.values.count : (accepted + soldOut + rateLimited + errors);

    const duration = data.metrics.http_req_duration || {};
    const vals = duration.values || {};
    const avg = vals['avg'] || 0;
    const p50 = vals['med'] || 0;
    const p90 = vals['p(90)'] || 0;
    const p95 = vals['p(95)'] || 0;

    const oversold = accepted > EXPECTED_STOCK;

    const summary = `
==========================================================
  Flash Sale v1 - k6 Load Test Results (500K comparable)
==========================================================

  Total Requests     : ${totalRequests}
  Purchases Accepted : ${accepted} (expected: ${EXPECTED_STOCK})
  Sold Out (409)     : ${soldOut}
  Rate Limited (429) : ${rateLimited}
  Errors (5xx/other) : ${errors}
  Timeouts           : ${timeouts}

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
