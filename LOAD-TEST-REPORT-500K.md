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

### Thresholds

- `http_req_duration`: p(95) < 10s  
- `http_req_failed`: rate < 0.999  
- `purchase_error`: rate < 0.10  
- `purchase_timeout`: rate < 0.10  

---

## Results

| Metric | Value |
|--------|--------|
| **Total requests** | 563,425 |
| **Total iterations** | 565,836 |
| **Test duration** | 2m 10.3s |
| **Peak VUs** | 10,000 |

### Response breakdown

| Code | Count | Description |
|------|--------|-------------|
| 202 Accepted | 10,000 | Purchase accepted (queued) |
| 409 Conflict | 553,425 | Sold out |
| 429 Too Many Requests | 0 | Rate limited |
| 5xx / timeout | 0 | Errors |

### Latency

| Metric | Value |
|--------|--------|
| **Avg** | 739.4 ms |
| **P50** | 559.3 ms |
| **P90** | 1,699.8 ms |
| **P95** | 2,230.2 ms |

### Correctness

- **Oversold:** No  
- **Verdict:** CORRECT – No overselling  

---

## Summary

- 563k requests at ~10k concurrent VUs, 35s hold at peak; no errors or timeouts.  
- All 10k accepted purchases matched expected stock; 553k sold-out (409) as designed.  
- p95 latency ~2.2s under sustained load; thresholds passed.
