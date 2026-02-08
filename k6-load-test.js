import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// Custom metrics
const purchaseSuccessRate = new Rate('purchase_success');
const purchaseSoldOutRate = new Rate('purchase_sold_out');
const purchaseErrorRate = new Rate('purchase_error');
const purchaseTimeoutRate = new Rate('purchase_timeout');
const purchaseResponseTime = new Trend('purchase_response_time');
const productViewResponseTime = new Trend('product_view_response_time');

// Configuration
export const options = {
    stages: [
        { duration: '30s', target: 100 },   // Ramp up to 100 users
        { duration: '1m', target: 500 },    // Ramp up to 500 users
        { duration: '2m', target: 1000 },   // Ramp up to 1000 users (peak load)
        { duration: '1m', target: 500 },    // Ramp down to 500 users
        { duration: '30s', target: 0 },      // Ramp down to 0 users
    ],
    thresholds: {
        // 95% of requests should complete within 5 seconds
        http_req_duration: ['p(95)<5000'],
        // Error rate should be less than 10%
        http_req_failed: ['rate<0.10'],
        // Purchase success rate should be tracked
        purchase_success: ['rate>0'],
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';

export default function () {
    const userId = `user_${__VU}_${__ITER}`; // Unique user ID per virtual user and iteration

    // Scenario 1: Browse products (simulate user viewing products page)
    const productsResponse = http.get(`${BASE_URL}/products`);
    check(productsResponse, {
        'products list status is 200': (r) => r.status === 200,
        'products list has data': (r) => r.body.length > 0,
    });
    productViewResponseTime.add(productsResponse.timings.duration);
    sleep(1); // Simulate user reading the page

    // Scenario 2: View specific product (simulate user clicking on product)
    const productResponse = http.get(`${BASE_URL}/products/${PRODUCT_ID}`);
    check(productResponse, {
        'product view status is 200': (r) => r.status === 200,
        'product has stock info': (r) => {
            if (r.status === 200) {
                const body = JSON.parse(r.body);
                return body.hasOwnProperty('stockQuantity');
            }
            return false;
        },
    });
    productViewResponseTime.add(productResponse.timings.duration);
    sleep(0.5); // Simulate user deciding to purchase

    // Scenario 3: Attempt purchase (main load test)
    const purchasePayload = JSON.stringify({
        userId: userId
    });

    const purchaseParams = {
        headers: {
            'Content-Type': 'application/json',
        },
        timeout: '30s', // 30 second timeout
    };

    const purchaseResponse = http.post(
        `${BASE_URL}/products/${PRODUCT_ID}/purchase`,
        purchasePayload,
        purchaseParams
    );

    const purchaseDuration = purchaseResponse.timings.duration;
    purchaseResponseTime.add(purchaseDuration);

    // Categorize purchase results
    if (purchaseResponse.status === 200) {
        const body = JSON.parse(purchaseResponse.body);
        check(purchaseResponse, {
            'purchase successful': (r) => r.status === 200 && body.success === true,
            'purchase has orderId': (r) => body.hasOwnProperty('orderId'),
            'purchase has stockRemaining': (r) => body.hasOwnProperty('stockRemaining'),
        });
        purchaseSuccessRate.add(1);
    } else if (purchaseResponse.status === 409) {
        // Sold out - this is expected behavior
        check(purchaseResponse, {
            'sold out response': (r) => r.status === 409,
            'sold out message': (r) => {
                const body = JSON.parse(r.body);
                return body.message && body.message.toLowerCase().includes('sold out');
            },
        });
        purchaseSoldOutRate.add(1);
    } else if (purchaseResponse.status === 503 || purchaseResponse.status === 504) {
        // Service unavailable or timeout - system overload
        purchaseErrorRate.add(1);
    } else if (purchaseResponse.status === 0 || purchaseResponse.timings.duration >= 30000) {
        // Request timeout or connection error
        purchaseTimeoutRate.add(1);
    } else {
        // Other errors (500, 400, etc.)
        purchaseErrorRate.add(1);
    }

    // Overall HTTP check
    check(purchaseResponse, {
        'purchase request completed': (r) => r.status !== 0,
    });

    sleep(1); // Think time between iterations
}

// Summary function - runs after test completes
export function handleSummary(data) {
    const summary = {
        timestamp: new Date().toISOString(),
        test_duration: `${data.state.testRunDurationMs / 1000}s`,
        metrics: {
            http_req_total: data.metrics.http_reqs.values.count,
            http_req_duration: {
                avg: `${data.metrics.http_req_duration.values.avg.toFixed(2)}ms`,
                min: `${data.metrics.http_req_duration.values.min.toFixed(2)}ms`,
                max: `${data.metrics.http_req_duration.values.max.toFixed(2)}ms`,
                p50: `${data.metrics.http_req_duration.values.med.toFixed(2)}ms`,
                p95: `${data.metrics.http_req_duration.values['p(95)'].toFixed(2)}ms`,
                p99: `${data.metrics.http_req_duration.values['p(99)'].toFixed(2)}ms`,
            },
            http_req_failed_rate: `${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%`,
            purchase_metrics: {
                success_rate: data.metrics.purchase_success ? `${(data.metrics.purchase_success.values.rate * 100).toFixed(2)}%` : '0%',
                sold_out_rate: data.metrics.purchase_sold_out ? `${(data.metrics.purchase_sold_out.values.rate * 100).toFixed(2)}%` : '0%',
                error_rate: data.metrics.purchase_error ? `${(data.metrics.purchase_error.values.rate * 100).toFixed(2)}%` : '0%',
                timeout_rate: data.metrics.purchase_timeout ? `${(data.metrics.purchase_timeout.values.rate * 100).toFixed(2)}%` : '0%',
            },
            purchase_response_time: data.metrics.purchase_response_time ? {
                avg: `${data.metrics.purchase_response_time.values.avg.toFixed(2)}ms`,
                p95: `${data.metrics.purchase_response_time.values['p(95)'].toFixed(2)}ms`,
                p99: `${data.metrics.purchase_response_time.values['p(99)'].toFixed(2)}ms`,
            } : null,
        },
    };

    return {
        'stdout': JSON.stringify(summary, null, 2),
        'summary.json': JSON.stringify(summary, null, 2),
    };
}
