package com.example.flash_sale_engine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stress Test for Flash Sale Engine v2 (Optimized)
 *
 * Tests the Redis-backed purchase flow:
 *   Rate Limit -> Redis Stock Check (Lua) -> Redis Stream Enqueue -> 202 Accepted
 *
 * Since v2 uses async order processing, this test also polls the order status
 * endpoint to verify all orders are eventually CONFIRMED in PostgreSQL.
 *
 * Usage:
 *   1. Start the Spring Boot application (Docker Compose will start Redis + PostgreSQL)
 *   2. Run: ./mvnw test-compile exec:java
 *
 * Expected results with 100k users and 100 stock:
 *   - 100 successful purchases (202 Accepted)
 *   - ~99,900 sold out responses (409)
 *   - Some rate-limited responses (429) depending on timing
 *   - P99 latency < 100ms (Redis hot path)
 *   - All 100 orders eventually CONFIRMED
 *   - No overselling
 */
public class StressTest {

    private static final String BASE_URL = "http://localhost:8080/api/v2";

    // Configuration
    private static final int TOTAL_USERS = 100_000;        // 100k concurrent users
    private static final int EXPECTED_STOCK = 100;          // Product stock
    private static final int REQUEST_TIMEOUT_SECONDS = 30;
    private static final Long PRODUCT_ID = 1L;

    // Metrics
    private static final AtomicInteger successCount = new AtomicInteger(0);     // 202 Accepted
    private static final AtomicInteger soldOutCount = new AtomicInteger(0);     // 409 Sold Out
    private static final AtomicInteger rateLimitedCount = new AtomicInteger(0); // 429 Rate Limited
    private static final AtomicInteger notStartedCount = new AtomicInteger(0);  // 425 Not Started
    private static final AtomicInteger errorCount = new AtomicInteger(0);       // 500 / connection errors
    private static final AtomicInteger timeoutCount = new AtomicInteger(0);     // Request timeouts
    private static final AtomicInteger completedCount = new AtomicInteger(0);

    private static final AtomicLong totalResponseTime = new AtomicLong(0);
    private static final List<Long> responseTimes = new CopyOnWriteArrayList<>();
    private static final List<String> orderIds = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("==========================================================");
        System.out.println("  Flash Sale Engine v2 - Stress Test (Redis-Optimized)");
        System.out.println("==========================================================");
        System.out.println("  Concurrent Users : " + TOTAL_USERS);
        System.out.println("  Expected Stock   : " + EXPECTED_STOCK);
        System.out.println("  Request Timeout  : " + REQUEST_TIMEOUT_SECONDS + "s");
        System.out.println("  Target           : " + BASE_URL);
        System.out.println("==========================================================\n");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                .build();

        // Verify product exists and sale is active
        System.out.println("Checking sale status...");
        HttpRequest statusReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/sale/status?productId=" + PRODUCT_ID))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> statusResp = client.send(statusReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Sale status: " + statusResp.body());
        if (statusResp.statusCode() != 200 || !statusResp.body().contains("ACTIVE")) {
            System.out.println("ERROR: Sale is not ACTIVE. Start the application and try again.");
            return;
        }

        // Create all threads with barrier synchronization
        System.out.println("\nCreating " + TOTAL_USERS + " virtual threads...");
        AtomicBoolean startFlag = new AtomicBoolean(false);
        CountDownLatch doneLatch = new CountDownLatch(TOTAL_USERS);
        AtomicInteger readyCount = new AtomicInteger(0);

        for (int i = 0; i < TOTAL_USERS; i++) {
            final String userId = "user_" + i;
            Thread.ofVirtual().start(() -> {
                readyCount.incrementAndGet();

                // Wait for start signal
                while (!startFlag.get()) {
                    Thread.onSpinWait();
                }

                long start = System.currentTimeMillis();
                try {
                    String json = String.format("{\"userId\":\"%s\"}", userId);
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(BASE_URL + "/products/" + PRODUCT_ID + "/purchase"))
                            .header("Content-Type", "application/json")
                            .header("X-User-Id", userId)
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                            .build();

                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    long elapsed = System.currentTimeMillis() - start;
                    totalResponseTime.addAndGet(elapsed);
                    responseTimes.add(elapsed);

                    switch (resp.statusCode()) {
                        case 202 -> {
                            successCount.incrementAndGet();
                            // Extract orderId from response
                            String body = resp.body();
                            int idx = body.indexOf("\"orderId\":\"");
                            if (idx >= 0) {
                                int end = body.indexOf("\"", idx + 11);
                                if (end > idx + 11) {
                                    orderIds.add(body.substring(idx + 11, end));
                                }
                            }
                        }
                        case 409 -> soldOutCount.incrementAndGet();
                        case 429 -> rateLimitedCount.incrementAndGet();
                        case 425 -> notStartedCount.incrementAndGet();
                        default -> errorCount.incrementAndGet();
                    }
                } catch (java.net.http.HttpTimeoutException e) {
                    long elapsed = System.currentTimeMillis() - start;
                    responseTimes.add(elapsed);
                    timeoutCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    completedCount.incrementAndGet();
                    doneLatch.countDown();
                }
            });

            // Progress reporting every 10k threads
            if ((i + 1) % 10_000 == 0) {
                System.out.printf("  Threads created: %d/%d (ready: %d)\r", i + 1, TOTAL_USERS, readyCount.get());
            }
        }

        // Wait for all threads to be ready
        System.out.println("\nWaiting for threads to reach barrier...");
        long waitStart = System.currentTimeMillis();
        while (readyCount.get() < TOTAL_USERS) {
            if (System.currentTimeMillis() - waitStart > 60_000) {
                System.out.println("WARNING: Only " + readyCount.get() + "/" + TOTAL_USERS + " threads ready after 60s. Proceeding...");
                break;
            }
            Thread.sleep(100);
        }
        System.out.println("All " + readyCount.get() + " threads ready. Releasing...\n");

        // Release all threads simultaneously
        long testStart = System.currentTimeMillis();
        startFlag.set(true);

        // Progress reporting while waiting
        Thread progressThread = Thread.ofVirtual().start(() -> {
            while (completedCount.get() < TOTAL_USERS) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                int done = completedCount.get();
                System.out.printf("  Progress: %d/%d (%.1f%%) | Success: %d | SoldOut: %d | RateLimited: %d | Errors: %d | Timeouts: %d\r",
                        done, TOTAL_USERS, (done * 100.0 / TOTAL_USERS),
                        successCount.get(), soldOutCount.get(), rateLimitedCount.get(),
                        errorCount.get(), timeoutCount.get());
            }
        });

        doneLatch.await();
        long testEnd = System.currentTimeMillis();
        long totalTime = testEnd - testStart;

        // Wait for progress thread to finish
        Thread.sleep(100);
        progressThread.interrupt();

        // === PHASE 2: Verify order completion ===
        System.out.println("\n\nVerifying order completion (async DB writes)...");
        int confirmedOrders = 0;
        int pendingOrders = 0;
        int failedOrders = 0;

        if (!orderIds.isEmpty()) {
            // Give the worker a moment to process
            Thread.sleep(3000);

            for (String orderId : orderIds) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(BASE_URL + "/orders/" + orderId + "/status"))
                            .GET()
                            .timeout(Duration.ofSeconds(5))
                            .build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    String body = resp.body();
                    if (body.contains("CONFIRMED")) {
                        confirmedOrders++;
                    } else if (body.contains("PENDING")) {
                        pendingOrders++;
                    } else {
                        failedOrders++;
                    }
                } catch (Exception e) {
                    failedOrders++;
                }
            }

            // If orders still pending, wait and retry
            if (pendingOrders > 0) {
                System.out.println("  " + pendingOrders + " orders still pending. Waiting 5s for worker...");
                Thread.sleep(5000);
                pendingOrders = 0;
                confirmedOrders = 0;
                failedOrders = 0;
                for (String orderId : orderIds) {
                    try {
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(BASE_URL + "/orders/" + orderId + "/status"))
                                .GET()
                                .timeout(Duration.ofSeconds(5))
                                .build();
                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        String body = resp.body();
                        if (body.contains("CONFIRMED")) confirmedOrders++;
                        else if (body.contains("PENDING")) pendingOrders++;
                        else failedOrders++;
                    } catch (Exception e) {
                        failedOrders++;
                    }
                }
            }
        }

        // === RESULTS ===
        System.out.println("\n==========================================================");
        System.out.println("  STRESS TEST RESULTS");
        System.out.println("==========================================================");

        System.out.println("\n  PURCHASE PHASE:");
        System.out.println("    Total Requests     : " + TOTAL_USERS);
        System.out.println("    202 Accepted       : " + successCount.get());
        System.out.println("    409 Sold Out       : " + soldOutCount.get());
        System.out.println("    429 Rate Limited   : " + rateLimitedCount.get());
        System.out.println("    425 Not Started    : " + notStartedCount.get());
        System.out.println("    Errors             : " + errorCount.get());
        System.out.println("    Timeouts           : " + timeoutCount.get());

        System.out.println("\n  ORDER VERIFICATION:");
        System.out.println("    Orders Enqueued    : " + orderIds.size());
        System.out.println("    CONFIRMED in DB    : " + confirmedOrders);
        System.out.println("    Still PENDING      : " + pendingOrders);
        System.out.println("    FAILED             : " + failedOrders);

        System.out.println("\n  TIMING:");
        System.out.println("    Total Wall Time    : " + totalTime + " ms (" + String.format("%.2f", totalTime / 1000.0) + "s)");
        System.out.println("    Throughput         : " + String.format("%.0f", TOTAL_USERS * 1000.0 / totalTime) + " req/sec");
        if (!responseTimes.isEmpty()) {
            System.out.println("    Avg Response Time  : " + String.format("%.2f", totalResponseTime.get() / (double) responseTimes.size()) + " ms");
        }

        // Percentiles
        if (!responseTimes.isEmpty()) {
            List<Long> sorted = new java.util.ArrayList<>(responseTimes);
            Collections.sort(sorted);
            long p50 = sorted.get(sorted.size() / 2);
            long p95 = sorted.get((int) (sorted.size() * 0.95));
            long p99 = sorted.get((int) (sorted.size() * 0.99));

            System.out.println("\n  RESPONSE TIME PERCENTILES:");
            System.out.println("    P50 (Median)       : " + p50 + " ms");
            System.out.println("    P95                : " + p95 + " ms");
            System.out.println("    P99                : " + p99 + " ms");
        }

        // Correctness validation
        System.out.println("\n  CORRECTNESS:");
        boolean noOverselling = successCount.get() <= EXPECTED_STOCK;
        boolean allOrdersConfirmed = confirmedOrders == orderIds.size();
        boolean purchaseCountCorrect = successCount.get() == EXPECTED_STOCK;

        System.out.println("    Purchases          : " + successCount.get() + " (expected: " + EXPECTED_STOCK + ")");
        System.out.println("    No Overselling     : " + (noOverselling ? "PASS" : "FAIL"));
        System.out.println("    All Orders in DB   : " + (allOrdersConfirmed ? "PASS (" + confirmedOrders + "/" + orderIds.size() + ")" : "FAIL (" + confirmedOrders + "/" + orderIds.size() + ")"));
        System.out.println("    Exact Stock Match  : " + (purchaseCountCorrect ? "PASS" : "WARN (" + successCount.get() + " != " + EXPECTED_STOCK + ")"));

        // Overall verdict
        System.out.println("\n  VERDICT:");
        if (noOverselling && allOrdersConfirmed && purchaseCountCorrect) {
            System.out.println("    ALL CHECKS PASSED - System is correct and performant!");
        } else if (noOverselling) {
            System.out.println("    PARTIAL PASS - No overselling, but some checks need attention");
        } else {
            System.out.println("    FAIL - Overselling detected!");
        }

        System.out.println("\n==========================================================\n");
    }
}
