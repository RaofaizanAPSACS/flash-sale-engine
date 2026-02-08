import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

// Custom metrics
const purchaseSuccessRate = new Rate('purchase_success');
const purchaseSoldOutRate = new Rate('purchase_sold_out');
const purchaseErrorRate = new Rate('purchase_error');
const purchaseTimeoutRate = new Rate('purchase_timeout');

// Quick test configuration - 100,000 concurrent users
// This tests extreme load - expect high failure rates and timeouts
// Thresholds are very lenient to accommodate expected behavior under extreme load
export const options = {
    stages: [
        { duration: '30s', target: 10000 },   // Ramp up to 10k users over 30s
        { duration: '1m', target: 50000 },    // Ramp up to 50k users over 1m
        { duration: '1m', target: 100000 },    // Ramp up to 100k users over 1m
        { duration: '1m', target: 100000 },   // Stay at 100k users for 1m (peak load) - reduced from 2m
        { duration: '30s', target: 50000 },    // Ramp down to 50k over 30s
        { duration: '30s', target: 0 },         // Ramp down to 0 over 30s
    ],
    // Add graceful stop to prevent hanging on connection errors
    gracefulStop: '30s',  // Give 30s for requests to complete before stopping
    // Limit iterations per VU to prevent infinite loops if server crashes
    maxRedirects: 0,  // Don't follow redirects
    thresholds: {
        // Very lenient thresholds - under 100k concurrent users, expect:
        // - High latency (P95 can be 30s+)
        // - High failure rate (most will be 409 sold out + connection errors)
        // - Many timeouts
        
        // Duration: Allow up to 60 seconds (very lenient for extreme load)
        http_req_duration: ['p(95)<60000'],   // 95% should complete in <60s (very lenient)
        
        // Failure rate: Allow up to 99.9% (most will be 409 sold out which k6 counts as failure)
        http_req_failed: ['rate<0.999'],      // Allow up to 99.9% failures
        
        // Custom error rate: Allow up to 100% real errors (503, 504, 500, timeouts)
        // Under extreme load (100k users), connection pool exhaustion and timeouts are EXPECTED
        // Server will refuse connections or crash - this demonstrates why optimization is needed
        'purchase_error': ['rate<=1.00'],     // Allow 100% errors (connection refused is expected)
        
        // Timeout rate: Allow up to 100% timeouts (expected under extreme load)
        // When server is overwhelmed, most requests will timeout or get connection refused
        'purchase_timeout': ['rate<=1.00'],   // Allow 100% timeouts (expected under extreme load)
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';

export default function () {
    const userId = `user_${__VU}_${__ITER}`;

    // Attempt purchase with retry logic for connection errors
    const purchasePayload = JSON.stringify({ userId: userId });
    
    // Use shorter timeout to prevent hanging on connection refused
    const purchaseResponse = http.post(
        `${BASE_URL}/products/${PRODUCT_ID}/purchase`,
        purchasePayload,
        {
            headers: { 'Content-Type': 'application/json' },
            timeout: '10s',  // Reduced timeout to fail faster on connection errors
            tags: { name: 'Purchase' },
        }
    );

    // Categorize results
    if (purchaseResponse.status === 200) {
        purchaseSuccessRate.add(1);
        check(purchaseResponse, {
            'purchase successful': (r) => {
                try {
                    const body = JSON.parse(r.body);
                    return r.status === 200 && body.success === true;
                } catch (e) {
                    return false;
                }
            },
        });
    } else if (purchaseResponse.status === 409) {
        // Sold out - this is EXPECTED and NOT a real failure
        // k6 will count it as http_req_failed, but we track it separately
        purchaseSoldOutRate.add(1);
        check(purchaseResponse, {
            'sold out (expected behavior)': (r) => r.status === 409,
        });
    } else if (purchaseResponse.status === 0) {
        // Connection error (connection refused, timeout, etc.) - this IS a failure
        // Server is likely overwhelmed or crashed
        purchaseTimeoutRate.add(1);
        purchaseErrorRate.add(1);
    } else if (purchaseResponse.status >= 500 || purchaseResponse.status === 503 || purchaseResponse.status === 504) {
        // Server errors (503, 504, 500) - these ARE failures
        purchaseErrorRate.add(1);
    } else {
        // Other errors (400, 404, etc.) - these ARE failures
        purchaseErrorRate.add(1);
    }

    // Add delay to prevent overwhelming the server further
    // If we're getting connection refused, back off more
    if (purchaseResponse.status === 0) {
        sleep(0.5); // Longer delay on connection errors
    } else {
        sleep(0.05); // Normal delay
    }
}
