# k6 Load Testing Guide

This guide explains how to run load tests against the Flash Sale Engine using k6.

## Prerequisites

1. **Install k6**: 
   - Windows: `choco install k6` or download from https://k6.io/docs/getting-started/installation/
   - Mac: `brew install k6`
   - Linux: See https://k6.io/docs/getting-started/installation/

2. **Start the Spring Boot application**:
   ```bash
   ./mvnw spring-boot:run
   ```

3. **Ensure database is running** (via Docker Compose):
   ```bash
   docker compose up -d
   ```

4. **Verify product exists**: The app should auto-create product ID 1 with 100 stock on startup.

## Test Scripts

### 1. Full Load Test (`k6-load-test.js`)
Comprehensive test with realistic user flow:
- Browse products
- View product details
- Attempt purchase
- Gradual ramp-up and ramp-down
- Detailed metrics and categorization

**Command:**
```bash
k6 run k6-load-test.js
```

**With custom settings:**
```bash
# Custom base URL
k6 run -e BASE_URL=http://localhost:8080/api/v1 k6-load-test.js

# Custom product ID
k6 run -e PRODUCT_ID=1 k6-load-test.js

# Both
k6 run -e BASE_URL=http://localhost:8080/api/v1 -e PRODUCT_ID=1 k6-load-test.js
```

### 2. Quick Test (`k6-quick-test.js`)
Simple burst test - 1000 concurrent users for 30 seconds:
- Direct purchase attempts only
- Fast execution
- Good for quick validation

**Command:**
```bash
k6 run k6-quick-test.js
```

## Understanding Results

### Key Metrics

1. **http_req_duration**: Response time percentiles (P50, P95, P99)
   - Shows how fast/slow requests are
   - P95 < 5s is good for normal load
   - P99 will be high under extreme load (expected)

2. **http_req_failed**: Overall failure rate
   - Includes connection errors, timeouts, 5xx errors
   - Under extreme load, expect high failure rate (this is normal for standard solution)

3. **purchase_success**: Rate of successful purchases (HTTP 200)
   - Should match available stock (100 items)
   - Shows system correctness

4. **purchase_sold_out**: Rate of "sold out" responses (HTTP 409)
   - Expected after stock is depleted
   - Shows system is correctly rejecting when out of stock

5. **purchase_error**: Rate of errors (503, 504, 500, connection errors)
   - High under extreme load (expected for standard solution)
   - Indicates system limitations

6. **purchase_timeout**: Rate of requests that exceeded 30s timeout
   - Shows requests that took too long
   - Common under extreme concurrent load

### Expected Behavior

**For Standard Solution (this branch):**
- ✅ **Correctness**: Exactly 100 successful purchases (no overselling)
- ⚠️ **Performance**: High error rate and timeouts under extreme load
- ⚠️ **P99 latency**: Very high (15-20+ seconds) under 1000+ concurrent users

This is **expected** - the standard solution is correct but not optimized for flash sale spikes.

## Example Output

```
✓ purchase successful
✓ purchase has orderId
✓ purchase has stockRemaining
✓ sold out response
✓ purchase request completed

checks.........................: 95.23% ✓ 47615    ✗ 2385
data_received..................: 2.1 MB 70 kB/s
data_sent......................: 1.2 MB 40 kB/s
http_req_duration..............: avg=8.5s    min=45ms    med=12.3s    max=30.1s    p(90)=18.2s    p(95)=22.1s
http_req_failed................: 47.62% ✓ 23850    ✗ 23750
http_reqs......................: 50000  1666.666667/s
iteration_duration.............: avg=9.2s    min=0.1s    med=13.1s    max=30.5s
purchase_error.................: 45.12% ✓ 22560
purchase_sold_out..............: 1.90%  ✓ 950
purchase_success...............: 0.20%  ✓ 100
purchase_timeout...............: 0.48%  ✓ 240
vus............................: 1000   min=1000    max=1000
```

## Advanced Usage

### Custom Load Profile

Edit the `stages` in `k6-load-test.js`:

```javascript
stages: [
    { duration: '1m', target: 100 },    // Ramp up to 100 users over 1 minute
    { duration: '3m', target: 100 },    // Stay at 100 users for 3 minutes
    { duration: '1m', target: 0 },      // Ramp down to 0 over 1 minute
],
```

### Output to JSON

```bash
k6 run --out json=results.json k6-load-test.js
```

### Cloud Execution (k6 Cloud)

```bash
k6 cloud k6-load-test.js
```

### Load Test Against Different Environment

```bash
k6 run -e BASE_URL=https://your-production-url.com/api/v1 k6-load-test.js
```

## Troubleshooting

**"Connection refused" errors:**
- Ensure Spring Boot app is running on port 8080
- Check: `curl http://localhost:8080/api/v1/products`

**All requests failing:**
- Check database is running: `docker compose ps`
- Check application logs for errors
- Verify product ID 1 exists

**High timeout rate:**
- This is expected under extreme load for standard solution
- Reduce concurrent users or increase timeout in script
- Consider this demonstrates why optimization is needed

## Comparison with Java Stress Test

The k6 test provides:
- ✅ More realistic HTTP load testing
- ✅ Better metrics visualization
- ✅ Easier to configure load profiles
- ✅ Can test against any environment (not just localhost)

The Java StressTest provides:
- ✅ Tests from within the same JVM
- ✅ More detailed error categorization
- ✅ Better for testing correctness (no network overhead)

Both are useful - use k6 for realistic load testing, use Java StressTest for correctness validation.
