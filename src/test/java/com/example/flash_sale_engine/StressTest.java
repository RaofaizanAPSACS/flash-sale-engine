package com.example.flash_sale_engine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stress Test for Flash Sale Engine
 *
 * Simulates concurrent users all trying to purchase the same product.
 * Tests correctness (no overselling) and measures performance.
 *
 * This is a STANDARD solution stress test. It will show:
 * - The system is CORRECT (exactly 100 purchases for 100 stock)
 * - The system CANNOT handle extreme concurrent load efficiently
 *   (high P99 latency due to row-level lock contention)
 *
 * Usage:
 *   1. Start the Spring Boot app
 *   2. Ensure product ID 1 exists with stock = 100
 *   3. Run: ./mvnw test-compile exec:java
 */
public class StressTest {

    private static final String BASE_URL = "http://localhost:8080/api/v1";
    private static final int TOTAL_USERS = 100_000;
    private static final int REQUEST_TIMEOUT_SECONDS = 30;
    private static final Long PRODUCT_ID = 1L;

    // Metrics
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger soldOutCount = new AtomicInteger(0);
    private static final AtomicInteger errorCount = new AtomicInteger(0);
    private static final AtomicInteger timeoutCount = new AtomicInteger(0);
    private static final AtomicInteger completedCount = new AtomicInteger(0);
    private static final AtomicLong totalResponseTime = new AtomicLong(0);
    private static final List<Long> responseTimes = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void main(String[] args) throws InterruptedException {
        System.out.println("==========================================================");
        System.out.println("  Flash Sale Stress Test");
        System.out.println("==========================================================");
        System.out.println("  Concurrent Users : " + TOTAL_USERS);
        System.out.println("  Product ID       : " + PRODUCT_ID);
        System.out.println("  Timeout          : " + REQUEST_TIMEOUT_SECONDS + "s");
        System.out.println("==========================================================\n");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Verify product exists before starting
        System.out.print("Checking product exists... ");
        try {
            HttpRequest checkReq = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/products/" + PRODUCT_ID))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> checkResp = client.send(checkReq, HttpResponse.BodyHandlers.ofString());
            if (checkResp.statusCode() != 200) {
                System.out.println("FAILED (status " + checkResp.statusCode() + ")");
                System.out.println("Product " + PRODUCT_ID + " not found. Start the app and ensure data is initialized.");
                return;
            }
            System.out.println("OK");
            System.out.println("  Response: " + checkResp.body());
        } catch (Exception e) {
            System.out.println("FAILED (" + e.getMessage() + ")");
            System.out.println("Is the server running at " + BASE_URL + "?");
            return;
        }

        // Create all threads, hold them at barrier
        System.out.println("\nCreating " + TOTAL_USERS + " threads...");
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
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                            .build();

                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    long elapsed = System.currentTimeMillis() - start;
                    totalResponseTime.addAndGet(elapsed);
                    responseTimes.add(elapsed);

                    if (resp.statusCode() == 200 && resp.body().contains("\"success\":true")) {
                        successCount.incrementAndGet();
                    } else if (resp.statusCode() == 409) {
                        soldOutCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                } catch (java.net.http.HttpTimeoutException e) {
                    long elapsed = start > 0 ? System.currentTimeMillis() - start : REQUEST_TIMEOUT_SECONDS * 1000L;
                    responseTimes.add(elapsed);
                    timeoutCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    completedCount.incrementAndGet();
                    doneLatch.countDown();
                }
            });
        }

        // Wait for all threads to be ready
        System.out.print("Waiting for threads to be ready... ");
        long waitStart = System.currentTimeMillis();
        while (readyCount.get() < TOTAL_USERS) {
            if (System.currentTimeMillis() - waitStart > 30_000) {
                System.out.println("\nTimeout: only " + readyCount.get() + "/" + TOTAL_USERS + " ready");
                break;
            }
            Thread.sleep(50);
        }
        System.out.println(readyCount.get() + "/" + TOTAL_USERS + " ready");

        // Fire
        System.out.println("Releasing all threads simultaneously...\n");
        Thread.sleep(200);
        long testStart = System.currentTimeMillis();
        startFlag.set(true);

        // Progress reporter
        Thread reporter = new Thread(() -> {
            while (completedCount.get() < TOTAL_USERS) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                System.out.printf("  [Progress] %d/%d completed (success=%d, soldOut=%d, error=%d, timeout=%d)\n",
                        completedCount.get(), TOTAL_USERS,
                        successCount.get(), soldOutCount.get(), errorCount.get(), timeoutCount.get());
            }
        });
        reporter.setDaemon(true);
        reporter.start();

        // Wait for completion
        doneLatch.await();
        long testDuration = System.currentTimeMillis() - testStart;

        // Calculate percentiles
        List<Long> sorted = new ArrayList<>(responseTimes);
        Collections.sort(sorted);
        long p50 = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        long p95 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.95));
        long p99 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.99));
        long avgMs = sorted.isEmpty() ? 0 : totalResponseTime.get() / sorted.size();

        // Results
        System.out.println("\n==========================================================");
        System.out.println("  RESULTS");
        System.out.println("==========================================================");
        System.out.println();
        System.out.println("  Requests");
        System.out.println("    Total      : " + TOTAL_USERS);
        System.out.println("    Successful : " + successCount.get());
        System.out.println("    Sold Out   : " + soldOutCount.get());
        System.out.println("    Errors     : " + errorCount.get());
        System.out.println("    Timeouts   : " + timeoutCount.get());
        System.out.println();
        System.out.println("  Timing");
        System.out.println("    Wall clock : " + testDuration + " ms (" + String.format("%.1f", testDuration / 1000.0) + "s)");
        System.out.println("    Throughput : " + String.format("%.1f", TOTAL_USERS * 1000.0 / testDuration) + " req/s");
        System.out.println("    Avg        : " + avgMs + " ms (" + String.format("%.2f", avgMs / 1000.0) + "s)");
        System.out.println("    P50        : " + p50 + " ms (" + String.format("%.2f", p50 / 1000.0) + "s)");
        System.out.println("    P95        : " + p95 + " ms (" + String.format("%.2f", p95 / 1000.0) + "s)");
        System.out.println("    P99        : " + p99 + " ms (" + String.format("%.2f", p99 / 1000.0) + "s)");
        System.out.println();
        System.out.println("  Correctness");
        System.out.println("    Purchases  : " + successCount.get() + " (expected: 100)");
        System.out.println("    Oversold   : " + (successCount.get() > 100 ? "YES - BUG" : "NO"));
        System.out.println("    Verdict    : " + (successCount.get() == 100 ? "CORRECT" : "INCORRECT"));
        System.out.println();
        System.out.println("==========================================================");
    }
}
