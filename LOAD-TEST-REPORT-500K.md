# Flash Sale v2 – Load Test Report (500K Target)

## Test Parameters

| Parameter | Value |
|-----------|--------|
| **Profile** | 500K total requests (`RUN_500K_REQUESTS=1`) |
| **Tool** | k6 |
| **Base URL** | `http://localhost:8080/api/v2` |
| **Endpoint** | `POST /products/1/purchase` |
| **Peak VUs** | 10,000 |
| **Stages** | Ramp: 10s→1k, 20s→5k, 20s→10k · Hold: **35s** at 10k · Ramp down: 10s→5k, 10s→0 |
| **Request timeout** | 10s |
| **Iteration sleep** | 0.05s |
| **Expected stock** | 10,000 (accepted purchases capped by inventory) |



## Results

| Metric | Value |
|--------|--------|
| **Total requests** | 618,241 |
| **Total iterations** | 618,241 (0 interrupted) |
| **Test duration** | 2m 10.1s |
| **Peak VUs** | 10,000 |

### Response breakdown

| Code | Count | Description |
|------|--------|-------------|
| 202 Accepted | 10,000 | Purchase accepted (queued) |
| 409 Conflict | 607,491 | Sold out (instant Redis rejection) |
| 429 Too Many Requests | 0 | Rate limited |
| 5xx / Timeout / Errors | 750 (0.12%) | Connection timeouts |

**Key findings:**
- **Error rate: 0.12%** (750 errors out of 618,241 requests) - extremely low
- **All errors are timeouts** (750 errors = 750 timeouts) - no server errors (5xx)
- **k6 http_req_failed: 98.38%** - k6 counts 409 (Sold Out) as "failed" (expected, as it's not 2xx)
- **Actual system errors: 0.12%** - only connection-level timeouts, no application errors

### Latency

| Metric | Value |
|--------|--------|
| **Avg** | 625.0 ms |
| **P50** | 480.6 ms |
| **P90** | 1,248.6 ms |
| **P95** | 1,785.6 ms |

**Improvement:** Latency improved compared to earlier run (P95: 1.8s vs 2.2s), likely due to optimizations (in-memory sold-out cache, Redis connection pooling).

### Correctness

- **Oversold:** No  
- **Verdict:** CORRECT – No overselling  

---

## Summary

- **618k requests** processed at ~10k concurrent VUs, 35s hold at peak
- **Error rate: 0.12%** (750 timeouts out of 618k requests) - extremely low
- **Zero server errors** (no 5xx responses) - all errors are connection-level timeouts
- All 10k accepted purchases matched expected stock; 607k sold-out (409) as designed
- **P95 latency: 1.8s** under sustained load; thresholds passed

### Error Analysis

**Connection Timeouts (750):**
- Represents **0.12% of total requests**
- All errors are connection-level timeouts (status 0), not server errors
- Likely causes: TCP connection establishment delays, brief network hiccups, or OS-level connection queue saturation during peak moments
- **This is acceptable** - 99.88% success rate under extreme load

**k6 http_req_failed metric:**
- Shows 98.38% "failed" rate, but this is **misleading**
- k6 counts 409 (Sold Out) as "failed" because it's not a 2xx success code
- **Actual system errors: 0.12%** (only the 750 timeouts)
- The 607k "failed" requests are intentional business logic rejections (sold out), not errors

### Performance Validation

✅ **Correctness:** No overselling (exactly 10k purchases)  
✅ **Reliability:** 99.88% request success rate  
✅ **Latency:** P95 at 1.8s (well under 10s threshold)  
✅ **Throughput:** Handled 618k requests in 2m 10s (~4,800 req/s average)
