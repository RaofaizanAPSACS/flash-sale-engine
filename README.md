# Flash Sale Engine - Standard Solution (v1)

A Spring Boot REST API for handling flash sales with **correctness guarantees** but **not optimized for extreme concurrent load**.

## Overview

This is a **standard e-commerce solution** that demonstrates:
- ✅ **Correctness**: Atomic database operations prevent overselling
- ✅ **Data Integrity**: Database constraints ensure stock never goes negative
- ⚠️ **Limitations**: Cannot handle extreme concurrent load (100k+ users) efficiently

**Purpose**: This branch serves as a baseline that works correctly but shows why optimization (message queues, caching, rate limiting) is needed for flash sale spikes.

## Quick Start

### Prerequisites
- Java 21+
- Maven
- Docker & Docker Compose

### Run the Application

1. **Start PostgreSQL database:**
   ```bash
   docker compose up -d
   ```

2. **Start the Spring Boot application:**
   ```bash
   ./mvnw spring-boot:run
   ```

3. **Verify it's running:**
   ```bash
   curl http://localhost:8080/api/v1/products
   ```

The application auto-creates a test product (ID: 1) with 100 stock on startup.

## API Endpoints

### Browse Products
```bash
# List all products
GET /api/v1/products

# Get specific product
GET /api/v1/products/{id}
```

### Purchase Product
```bash
POST /api/v1/products/{id}/purchase
Content-Type: application/json

{
  "userId": "user123"
}
```

**Response Codes:**
- `200` - Purchase successful
- `409` - Sold out
- `404` - Product not found
- `503` - Service unavailable (database timeout/connection issue)
- `504` - Request timeout
- `500` - Internal server error

**Success Response:**
```json
{
  "orderId": 1,
  "message": "Purchase successful",
  "success": true,
  "stockRemaining": 99
}
```

## How It Works

### Purchase Flow
1. **Atomic SQL Update**: `UPDATE inventory SET stock_quantity = stock_quantity - 1 WHERE id = :id AND stock_quantity > 0`
2. **Database Constraint**: `CHECK (stock_quantity >= 0)` prevents negative stock
3. **Order Creation**: Only if stock decrement succeeds

### Why It's Correct
- Single atomic SQL statement (no read-before-write race condition)
- Database-level constraint as safety net
- Transaction ensures consistency

### Why It's Not Optimized
- All requests serialize on the same row lock
- Connection pool becomes bottleneck (only 10 connections)
- No caching, no message queue, no rate limiting
- Server crashes/refuses connections under extreme load

## Testing

### Load Testing with k6

**500K comparison test (same profile as v2):**
```bash
k6 run k6-v1-load-test-500k.js
```

**Quick test (100k users):**
```bash
k6 run k6-quick-test.js
```

**Full test (realistic user flow):**
```bash
k6 run k6-load-test.js
```

**Expected Results:**
- ✅ Exactly 100 successful purchases (matches stock)
- ⚠️ High error rate under extreme load (expected)
- ⚠️ High P99 latency (15-30+ seconds) under 1000+ concurrent users

**See [LOAD-TEST-REPORT-V1.md](./LOAD-TEST-REPORT-V1.md) for detailed 500K load test results.**

### Java Stress Test

```bash
./mvnw test-compile exec:java
```

Tests correctness with 1,000 concurrent users. Verifies no overselling occurs.

## Architecture

- **Framework**: Spring Boot 4.0.2
- **Database**: PostgreSQL 16
- **ORM**: JPA/Hibernate
- **Connection Pool**: HikariCP (10 connections)
- **Server**: Embedded Tomcat

## Configuration

Key settings in `application.properties`:
- Database connection pool: 10 connections
- Transaction timeout: 5 seconds
- Query timeout: 5 seconds

## Database Schema

**Inventory Table:**
- `id` (Primary Key)
- `product_name` (Unique)
- `stock_quantity` (with CHECK constraint: `>= 0`)
- `version` (Optimistic locking)

**Orders Table:**
- `id` (Primary Key)
- `user_id`
- `product_id` (Foreign Key)
- `status` (CONFIRMED/FAILED)
- `created_at`

## What This Demonstrates

### ✅ Correctness
- No overselling (exactly 100 purchases for 100 stock)
- Database constraints prevent data corruption
- Atomic operations ensure consistency

### ⚠️ Performance Limitations
- Connection pool exhaustion under high load
- Row-level lock contention (all requests compete for same row)
- Server crashes/refuses connections with 100k+ concurrent users
- High latency (P99: 15-30+ seconds) under extreme load

## Next Steps

This standard solution demonstrates **why optimization is needed**. Future improvements would include:
- Message queue (Kafka) for request buffering
- Caching layer (Redis) for stock checks
- Rate limiting to prevent overload
- Connection pool scaling
- Load balancing

## License

This is a demonstration project for learning purposes.
