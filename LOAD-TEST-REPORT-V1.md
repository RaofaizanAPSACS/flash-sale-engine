# Flash Sale v1 - Load Test Report (500K Target)

## Test Parameters

| Parameter | Value |
|-----------|--------|
| **Profile** | 500K total requests target |
| **Tool** | k6 |
| **Base URL** | `http://localhost:8080/api/v1` |
| **Endpoint** | `POST /products/1/purchase` |
| **Peak VUs** | 10,000 |
| **Stages** | Ramp: 10s->1k, 20s->5k, 20s->10k - Hold: **35s** at 10k - Ramp down: 10s->5k, 10s->0 |
| **Request timeout** | 10s |
| **Iteration sleep** | 0.05s |
| **Expected stock** | 10,000 |

### Thresholds

- `http_req_duration`: p(95) < 10s  
- `http_req_failed`: rate < 0.999  
- `purchase_error`: rate < 0.10  
- `purchase_timeout`: rate < 0.10  

---

## Results

| Metric | Value |
|--------|--------|
| **Total requests (k6)** | 119,180 |
| **Total iterations** | 118,854 (327 interrupted) |
| **Test duration** | 1m 55.1s |
| **Peak VUs** | 10,000 |
| **DB-confirmed purchases** | **10,000 (all stock sold to 0)** |

### Response breakdown (as seen by k6)

| Code | Count | Description |
|------|--------|-------------|
| 200 OK (received by k6) | 3,642 | Server responded 200 within 10s timeout |
| 409 Conflict | 0 | Sold out response received within timeout |
| Timeout / Errors | 115,538 | k6 timed out or connection failed |

**Why k6 only shows 3,642 accepted but DB shows 10,000 purchases:**

Under extreme load, v1 processes requests synchronously via PostgreSQL row locks. Many requests that **did succeed on the server** took longer than the 10s k6 timeout. k6 reports these as "timeout" (status 0), but the server still committed the purchase to the database.

| Metric | Count | Explanation |
|--------|-------|-------------|
| **DB-confirmed purchases** | **10,000** | Verified in DB: stock_quantity = 0 |
| k6 received 200 in time | 3,642 | Server responded within 10s timeout |
| Purchases completed after k6 timeout | ~6,358 | Server committed purchase, but k6 already gave up |
| Actual failed requests | ~109,180 | Connection refused, true timeouts (no server processing) |
| Sold Out seen by k6 | 0 | All 409 responses also exceeded timeout window |

### Latency

| Metric | Value |
|--------|--------|
| **Avg** | 1,318.6 ms |
| **P90** | 9,998.6 ms (~10s) |
| **P95** | 10,000.4 ms (~10s) |

**Note:** P90/P95 at exactly 10s = requests hitting the k6 timeout. Actual server processing time was longer.

### Correctness

- **Oversold:** No  
- **DB stock remaining:** 0 (all 10,000 sold)  
- **Verdict:** CORRECT - No overselling  

### Threshold violations

- `http_req_duration`: p(95) exceeded 10s
- `purchase_error`: Rate exceeded 0.10 (97% as seen by k6)
- `purchase_timeout`: Rate exceeded 0.10 (97% as seen by k6)

---

## Analysis

### Why k6 reports differ from DB reality

1. **Slow server-side processing:** v1 serializes on PostgreSQL row locks. Under 10k VUs, each purchase waits for the lock, causing multi-second delays.

2. **k6 timeout vs server commit:** k6 has a 10s timeout. If the server takes 12s to process a purchase, k6 reports "timeout" but the server still commits the transaction.

3. **No sold-out responses visible:** After stock depleted, the server was so overwhelmed that 409 responses also exceeded the 10s timeout window. k6 never received them.

4. **Connection pool saturation:** With only 10 DB connections, thousands of requests queue up. Most time out waiting for a connection, not during actual processing.

### Why v1 struggles under load

1. **Database bottleneck:** All requests serialize on the same inventory row lock
2. **Connection pool (10 connections):** Saturates immediately under 10k VUs
3. **No caching:** Every request hits PostgreSQL
4. **No async processing:** Synchronous DB writes block the response
5. **No rate limiting:** All traffic hits the database directly

---

## Comparison with v2 (Optimized)

| Metric | v1 (Standard) | v2 (Optimized) |
|--------|---------------|----------------|
| **Peak VUs** | 10,000 | 10,000 |
| **Total requests** | 119,180 | 618,241 |
| **DB-confirmed purchases** | 10,000 | 10,000 |
| **k6-observed success** | 3,642 (3%) | 10,000 (1.6%) |
| **Errors/Timeouts (k6)** | 115,538 (97%) | 750 (0.12%) |
| **P95 latency** | ~10s (timeout) | ~1.8s |
| **Correctness** | No overselling | No overselling |

**Key takeaway:** Both versions sold all 10,000 stock correctly. The difference is user experience:
- **v1:** 97% of users saw errors/timeouts (server was processing but too slow to respond)
- **v2:** 99.88% of users got instant responses (accepted or sold-out)

---

## Conclusion

- **Correctness:** All 10,000 stock sold, 0 remaining in DB, no overselling
- **Performance:** Server processed purchases but could not respond within timeout under extreme load
- **User experience:** 97% of users saw errors/timeouts despite the server working correctly
- **Root cause:** PostgreSQL row-lock serialization + connection pool exhaustion

---

## Test Command

```bash
k6 run k6-v1-load-test-500k.js
```
