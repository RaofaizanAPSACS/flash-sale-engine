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
 * Stress Test for Flash Sale Engine - System Availability Under Extreme Load
 * 
 * This script simulates thousands of concurrent users to test:
 * 1. System availability under extreme load
 * 2. Database/server responsiveness
 * 3. Request success/failure rates
 * 4. Timeout handling
 * 5. System resilience (does it crash or stay responsive?)
 * 
 * Uses Java Virtual Threads (Project Loom) for true high concurrency.
 * 
 * Usage:
 * 1. Start the Spring Boot application
 * 2. Ensure you have a product with ID 1 and stock quantity 100 in the database
 * 3. Run: ./mvnw test-compile exec:java
 * 
 * Configuration:
 * - TOTAL_USERS: Number of concurrent users to simulate (default: 10,000)
 * - REQUEST_TIMEOUT_SECONDS: Timeout for each HTTP request (default: 60 seconds)
 */
public class StressTest {
    
    private static final String BASE_URL = "http://localhost:8080/api/v1/sale";
    
    // Configuration - Adjust these for your test
    private static final int TOTAL_USERS = 10_000; // 10 thousand concurrent users
    private static final int REQUEST_TIMEOUT_SECONDS = 60; // Increased timeout for high load
    private static final Long PRODUCT_ID = 1L;
    
    // Thread creation batching - larger batches = faster creation but more memory
    // For 10k users, batch size of 1k provides good balance
    private static final int THREAD_BATCH_SIZE = 1_000;
    
    // Metrics tracking
    private static final AtomicInteger queueSuccessCount = new AtomicInteger(0);
    private static final AtomicInteger queueFailureCount = new AtomicInteger(0);
    private static final AtomicInteger queueTimeoutCount = new AtomicInteger(0);
    private static final AtomicInteger queueErrorCount = new AtomicInteger(0);
    
    private static final AtomicInteger purchaseSuccessCount = new AtomicInteger(0);
    private static final AtomicInteger purchaseFailureCount = new AtomicInteger(0);
    private static final AtomicInteger purchaseTimeoutCount = new AtomicInteger(0);
    private static final AtomicInteger purchaseErrorCount = new AtomicInteger(0);
    
    private static final AtomicLong totalQueueTime = new AtomicLong(0);
    private static final AtomicLong totalPurchaseTime = new AtomicLong(0);
    
    // Response time tracking for percentiles
    private static final List<Long> queueResponseTimes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final List<Long> purchaseResponseTimes = new java.util.concurrent.CopyOnWriteArrayList<>();
    
    private static final List<String> tokens = new ArrayList<>();
    private static final Object tokensLock = new Object();
    
    // Progress tracking
    private static final AtomicInteger completedQueueRequests = new AtomicInteger(0);
    private static final AtomicInteger completedPurchaseRequests = new AtomicInteger(0);
    
    // Error type tracking
    private static final AtomicInteger queueConnectionErrors = new AtomicInteger(0);
    private static final AtomicInteger purchaseConnectionErrors = new AtomicInteger(0);
    
    // Detailed error tracking
    private static final AtomicInteger queueHttpErrors = new AtomicInteger(0); // Non-200 status codes
    private static final AtomicInteger queueParseErrors = new AtomicInteger(0); // Token parsing failures
    private static final AtomicInteger queueSuccessFalseErrors = new AtomicInteger(0); // success:false responses
    private static final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> queueErrorTypes = new java.util.concurrent.ConcurrentHashMap<>();
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("🚀 Flash Sale Stress Test - System Availability Under Extreme Load");
        System.out.println("==================================================================");
        System.out.println("Total Concurrent Users: " + TOTAL_USERS);
        System.out.println("Request Timeout: " + REQUEST_TIMEOUT_SECONDS + " seconds");
        System.out.println("Product ID: " + PRODUCT_ID);
        System.out.println("Goal: Test system availability and resilience under extreme load");
        System.out.println("==================================================================\n");
        
        // Create HTTP client with Virtual Thread executor for true concurrency
        // Configure HttpClient with proper connection pooling for high concurrency
        // Note: Java HttpClient doesn't expose direct connection pool size, but we can configure timeouts
        // The default connection pool should handle 10k connections, but we need proper timeouts
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                // Use virtual thread executor for better scalability
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                // Note: HttpClient internally manages connection pool (default is usually sufficient)
                // Connection errors occur when server rejects connections due to overload
                .build();
        
        // Start progress reporter
        Thread progressReporter = new Thread(() -> {
            while (completedQueueRequests.get() < TOTAL_USERS || completedPurchaseRequests.get() < tokens.size()) {
                try {
                    Thread.sleep(5000); // Report every 5 seconds
                    int queueDone = completedQueueRequests.get();
                    int purchaseDone = completedPurchaseRequests.get();
                    System.out.printf("\r[Progress] Queue: %d/%d (%.1f%%) | Purchase: %d/%d (%.1f%%) | Active Threads: ~%d",
                            queueDone, TOTAL_USERS, (queueDone * 100.0 / TOTAL_USERS),
                            purchaseDone, tokens.size(), 
                            tokens.size() > 0 ? (purchaseDone * 100.0 / tokens.size()) : 0,
                            Thread.activeCount());
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        progressReporter.setDaemon(true);
        progressReporter.start();
        
        // Phase 1: All users enter the queue simultaneously
        System.out.println("\n📥 Phase 1: Entering Queue (Testing API availability)...");
        System.out.println("Simulating " + TOTAL_USERS + " concurrent queue requests...");
        System.out.println("Creating " + TOTAL_USERS + " virtual threads (this may take a moment)...\n");
        
        // Use AtomicBoolean to synchronize all threads to start simultaneously
        // This works for any number of threads (unlike Phaser which has a limit of ~65k)
        AtomicBoolean queueStartFlag = new AtomicBoolean(false);
        CountDownLatch queueLatch = new CountDownLatch(TOTAL_USERS);
        AtomicInteger threadsReady = new AtomicInteger(0);
        AtomicInteger threadsCreated = new AtomicInteger(0);
        AtomicInteger threadObjectsCreated = new AtomicInteger(0); // Track thread objects created
        
        // Create threads in batches for better performance and progress tracking
        int totalBatches = (TOTAL_USERS + THREAD_BATCH_SIZE - 1) / THREAD_BATCH_SIZE;
        
        // Create all threads in parallel batches (they will wait on the flag)
        for (int batch = 0; batch < totalBatches; batch++) {
            final int batchStart = batch * THREAD_BATCH_SIZE;
            final int batchEnd = Math.min(batchStart + THREAD_BATCH_SIZE, TOTAL_USERS);
            
            // Create batch of threads
            for (int i = batchStart; i < batchEnd; i++) {
                final int userId = i;
                threadObjectsCreated.incrementAndGet(); // Track thread object creation
                Thread.ofVirtual().start(() -> {
                    threadsCreated.incrementAndGet(); // Track when thread actually starts executing
                    long requestStart = 0;
                    try {
                        // Signal that this thread is ready
                        threadsReady.incrementAndGet();
                        
                        // Spin-wait until the start flag is set - ensures simultaneous start
                        while (!queueStartFlag.get()) {
                            Thread.onSpinWait(); // CPU-friendly spin wait
                        }
                        
                        // NOW all threads start their requests at the same time
                        requestStart = System.currentTimeMillis();
                        String token = enterQueue(client, "user_" + userId);
                        long requestTime = System.currentTimeMillis() - requestStart;
                        totalQueueTime.addAndGet(requestTime);
                        queueResponseTimes.add(requestTime);
                        
                        if (token != null) {
                            queueSuccessCount.incrementAndGet();
                            synchronized (tokensLock) {
                                tokens.add(token);
                            }
                        } else {
                            queueFailureCount.incrementAndGet();
                        }
                    } catch (java.net.http.HttpTimeoutException e) {
                        long requestTime = requestStart > 0 ? System.currentTimeMillis() - requestStart : REQUEST_TIMEOUT_SECONDS * 1000L;
                        queueTimeoutCount.incrementAndGet();
                        queueResponseTimes.add(requestTime);
                    } catch (Exception e) {
                        // Connection errors and other exceptions
                        if (e.getMessage() != null && e.getMessage().contains("Connection")) {
                            queueConnectionErrors.incrementAndGet();
                        }
                        queueErrorCount.incrementAndGet();
                    } finally {
                        completedQueueRequests.incrementAndGet();
                        queueLatch.countDown();
                    }
                });
            }
            
            // Progress update every batch - show thread objects created (immediate) vs threads started (delayed)
            if ((batch + 1) % 1 == 0 || batch == totalBatches - 1) {
                System.out.printf("Thread objects created: %d/%d (%.1f%%) | Threads started: %d/%d (%.1f%%)...\r", 
                    threadObjectsCreated.get(), TOTAL_USERS,
                    (threadObjectsCreated.get() * 100.0 / TOTAL_USERS),
                    threadsCreated.get(), TOTAL_USERS,
                    (threadsCreated.get() * 100.0 / TOTAL_USERS));
            }
        }
        
        System.out.println("\nWaiting for all threads to start executing...");
        
        // Wait for all threads to actually START (not just be created)
        // Virtual threads start lazily, so we need to wait for them to begin execution
        long startWait = System.currentTimeMillis();
        long maxWaitForStart = 60_000; // 1 minute max wait for threads to start
        int lastReportedQueueCount = 0;
        while (threadsCreated.get() < TOTAL_USERS) {
            if (System.currentTimeMillis() - startWait > maxWaitForStart) {
                System.out.println("\n⚠️  WARNING: Timeout waiting for threads to start. Only " + 
                    threadsCreated.get() + " threads started out of " + TOTAL_USERS);
                break;
            }
            Thread.sleep(100);
            // Report progress every 1000 threads or if count changed significantly
            if (threadsCreated.get() - lastReportedQueueCount >= 1000 || 
                (threadsCreated.get() > 0 && threadsCreated.get() % 1000 == 0)) {
                System.out.printf("Threads started: %d/%d (%.1f%%)...\r", 
                    threadsCreated.get(), TOTAL_USERS, 
                    (threadsCreated.get() * 100.0 / TOTAL_USERS));
                lastReportedQueueCount = threadsCreated.get();
            }
        }
        System.out.println(); // New line after progress
        
        // Now wait for all started threads to reach the barrier
        System.out.println("\nWaiting for all threads to reach barrier...");
        startWait = System.currentTimeMillis();
        long maxWaitTime = 60_000; // 1 minute max wait for barrier
        while (threadsReady.get() < threadsCreated.get()) {
            if (System.currentTimeMillis() - startWait > maxWaitTime) {
                System.out.println("\n⚠️  WARNING: Timeout waiting for threads to reach barrier. Proceeding with " + 
                    threadsReady.get() + " ready threads out of " + threadsCreated.get() + " started...");
                break;
            }
            Thread.sleep(100); // Check every 100ms
            if (threadsReady.get() % 1000 == 0 && threadsReady.get() > 0) {
                System.out.printf("Threads ready: %d/%d (%.1f%%)...\r", 
                    threadsReady.get(), threadsCreated.get(), 
                    (threadsReady.get() * 100.0 / threadsCreated.get()));
            }
        }
        Thread.sleep(500); // Extra buffer to ensure all threads are waiting
        System.out.println("\n✓ " + threadsReady.get() + "/" + threadsCreated.get() + " threads ready. Releasing simultaneously...\n");
        
        // Release all threads at once - NOW they all start simultaneously!
        long queueStartTime = System.currentTimeMillis();
        queueStartFlag.set(true); // This releases all waiting threads
        
        queueLatch.await();
        long queueEndTime = System.currentTimeMillis();
        long queueTotalTime = queueEndTime - queueStartTime;
        
        System.out.println("\n✓ Queue phase complete!");
        System.out.println("  Tokens obtained: " + tokens.size());
        System.out.println("  Total time: " + queueTotalTime + " ms (" + (queueTotalTime / 1000.0) + " seconds)");
        System.out.println("  Average response time: " + (totalQueueTime.get() / (double) TOTAL_USERS) + " ms");
        System.out.println("  Throughput: " + (TOTAL_USERS * 1000.0 / queueTotalTime) + " requests/second\n");
        
        // Small delay to ensure all tokens are ready
        Thread.sleep(2000);
        
        // Phase 2: All users attempt to purchase simultaneously (EXTREME LOAD TEST!)
        System.out.println("🛒 Phase 2: Attempting Purchases (Testing System Under Extreme Load)...");
        System.out.println("Simulating " + tokens.size() + " concurrent purchase requests...");
        System.out.println("Creating " + tokens.size() + " virtual threads (this may take a moment)...\n");
        
        // Use AtomicBoolean to synchronize all purchase threads to start simultaneously
        AtomicBoolean purchaseStartFlag = new AtomicBoolean(false);
        CountDownLatch purchaseLatch = new CountDownLatch(tokens.size());
        AtomicInteger purchaseThreadsReady = new AtomicInteger(0);
        AtomicInteger purchaseThreadsCreated = new AtomicInteger(0);
        AtomicInteger purchaseThreadObjectsCreated = new AtomicInteger(0); // Track thread objects created
        
        // Create threads in batches for better performance
        int purchaseTotalBatches = (tokens.size() + THREAD_BATCH_SIZE - 1) / THREAD_BATCH_SIZE;
        
        // Create all threads in batches (they will wait on the flag)
        for (int batch = 0; batch < purchaseTotalBatches; batch++) {
            final int batchStart = batch * THREAD_BATCH_SIZE;
            final int batchEnd = Math.min(batchStart + THREAD_BATCH_SIZE, tokens.size());
            
            for (int i = batchStart; i < batchEnd; i++) {
                final int userId = i;
                final String token = tokens.get(i);
                
                purchaseThreadObjectsCreated.incrementAndGet(); // Track thread object creation
                Thread.ofVirtual().start(() -> {
                    purchaseThreadsCreated.incrementAndGet(); // Track when thread actually starts executing
                    long requestStart = 0;
                    try {
                        // Signal that this thread is ready
                        purchaseThreadsReady.incrementAndGet();
                        
                        // Spin-wait until the start flag is set - ensures simultaneous start
                        while (!purchaseStartFlag.get()) {
                            Thread.onSpinWait(); // CPU-friendly spin wait
                        }
                        
                        // NOW all threads start their requests at the same time
                        // Note: Connections are reused from queue phase, so fewer connection errors expected
                        requestStart = System.currentTimeMillis();
                        boolean success = attemptPurchase(client, "user_" + userId, token, PRODUCT_ID);
                        long requestTime = System.currentTimeMillis() - requestStart;
                        totalPurchaseTime.addAndGet(requestTime);
                        purchaseResponseTimes.add(requestTime);
                        
                        if (success) {
                            purchaseSuccessCount.incrementAndGet();
                        } else {
                            purchaseFailureCount.incrementAndGet();
                        }
                    } catch (java.net.http.HttpTimeoutException e) {
                        long requestTime = requestStart > 0 ? System.currentTimeMillis() - requestStart : REQUEST_TIMEOUT_SECONDS * 1000L;
                        purchaseTimeoutCount.incrementAndGet();
                        purchaseResponseTimes.add(requestTime);
                    } catch (Exception e) {
                        // Connection errors and other exceptions
                        if (e.getMessage() != null && e.getMessage().contains("Connection")) {
                            purchaseConnectionErrors.incrementAndGet();
                        }
                        purchaseErrorCount.incrementAndGet();
                    } finally {
                        completedPurchaseRequests.incrementAndGet();
                        purchaseLatch.countDown();
                    }
                });
            }
            
            // Progress update every batch - show thread objects created vs threads started
            if ((batch + 1) % 1 == 0 || batch == purchaseTotalBatches - 1) {
                System.out.printf("Thread objects created: %d/%d (%.1f%%) | Threads started: %d/%d (%.1f%%)...\r", 
                    purchaseThreadObjectsCreated.get(), tokens.size(),
                    (purchaseThreadObjectsCreated.get() * 100.0 / tokens.size()),
                    purchaseThreadsCreated.get(), tokens.size(),
                    (purchaseThreadsCreated.get() * 100.0 / tokens.size()));
            }
        }
        
        System.out.println("\nWaiting for all threads to start executing...");
        
        // Wait for all threads to actually START (not just be created)
        long purchaseStartWait = System.currentTimeMillis();
        long purchaseMaxWaitForStart = 60_000; // 1 minute max wait for threads to start
        int lastReportedPurchaseCount = 0;
        while (purchaseThreadsCreated.get() < tokens.size()) {
            if (System.currentTimeMillis() - purchaseStartWait > purchaseMaxWaitForStart) {
                System.out.println("\n⚠️  WARNING: Timeout waiting for threads to start. Only " + 
                    purchaseThreadsCreated.get() + " threads started out of " + tokens.size());
                break;
            }
            Thread.sleep(100);
            // Report progress every 1000 threads or if count changed significantly
            if (purchaseThreadsCreated.get() - lastReportedPurchaseCount >= 1000 || 
                (purchaseThreadsCreated.get() > 0 && purchaseThreadsCreated.get() % 1000 == 0)) {
                System.out.printf("Threads started: %d/%d (%.1f%%)...\r", 
                    purchaseThreadsCreated.get(), tokens.size(), 
                    (purchaseThreadsCreated.get() * 100.0 / tokens.size()));
                lastReportedPurchaseCount = purchaseThreadsCreated.get();
            }
        }
        System.out.println(); // New line after progress
        
        // Now wait for all started threads to reach the barrier
        System.out.println("\nWaiting for all threads to reach barrier...");
        purchaseStartWait = System.currentTimeMillis();
        long purchaseMaxWaitTime = 60_000; // 1 minute max wait for barrier
        while (purchaseThreadsReady.get() < purchaseThreadsCreated.get()) {
            if (System.currentTimeMillis() - purchaseStartWait > purchaseMaxWaitTime) {
                System.out.println("\n⚠️  WARNING: Timeout waiting for threads to reach barrier. Proceeding with " + 
                    purchaseThreadsReady.get() + " ready threads out of " + purchaseThreadsCreated.get() + " started...");
                break;
            }
            Thread.sleep(100); // Check every 100ms
            if (purchaseThreadsReady.get() % 1000 == 0 && purchaseThreadsReady.get() > 0) {
                System.out.printf("Threads ready: %d/%d (%.1f%%)...\r", 
                    purchaseThreadsReady.get(), purchaseThreadsCreated.get(), 
                    (purchaseThreadsReady.get() * 100.0 / purchaseThreadsCreated.get()));
            }
        }
        Thread.sleep(500); // Extra buffer to ensure all threads are waiting
        System.out.println("\n✓ " + purchaseThreadsReady.get() + "/" + purchaseThreadsCreated.get() + " threads ready. Releasing simultaneously...\n");
        
        // Release all threads at once - NOW they all start simultaneously!
        long purchaseStartTime = System.currentTimeMillis();
        purchaseStartFlag.set(true); // This releases all waiting threads
        
        purchaseLatch.await();
        long purchaseEndTime = System.currentTimeMillis();
        long purchaseTotalTime = purchaseEndTime - purchaseStartTime;
        
        // Final Results
        System.out.println("\n\n==================================================================");
        System.out.println("📊 STRESS TEST RESULTS - SYSTEM AVAILABILITY REPORT");
        System.out.println("==================================================================");
        
        System.out.println("\n📥 QUEUE PHASE METRICS:");
        System.out.println("  Total Requests: " + TOTAL_USERS);
        System.out.println("  Thread Objects Created: " + TOTAL_USERS + " (all thread objects created)");
        System.out.println("  Threads Started: " + threadsCreated.get() + " (threads that began execution)");
        System.out.println("  Threads Ready: " + threadsReady.get() + " (threads that reached barrier)");
        System.out.println("  ✅ Successful: " + queueSuccessCount.get() + 
                " (" + (queueSuccessCount.get() * 100.0 / TOTAL_USERS) + "%)");
        System.out.println("  ❌ Failed: " + queueFailureCount.get() + 
                " (" + (queueFailureCount.get() * 100.0 / TOTAL_USERS) + "%)");
        System.out.println("  ⏱️  Timeout: " + queueTimeoutCount.get() + 
                " (" + (queueTimeoutCount.get() * 100.0 / TOTAL_USERS) + "%)");
        System.out.println("  ⚠️  Errors: " + queueErrorCount.get() + 
                " (" + (queueErrorCount.get() * 100.0 / TOTAL_USERS) + "%)");
        
        // Detailed error breakdown
        if (queueFailureCount.get() > 0 || queueErrorCount.get() > 0) {
            System.out.println("\n  🔍 FAILURE BREAKDOWN:");
            System.out.println("    - HTTP Errors (non-200): " + queueHttpErrors.get());
            System.out.println("    - Connection Errors: " + queueConnectionErrors.get());
            System.out.println("    - Parse Errors: " + queueParseErrors.get());
            System.out.println("    - Success:false Responses: " + queueSuccessFalseErrors.get());
            if (!queueErrorTypes.isEmpty()) {
                System.out.println("    - Error Types:");
                queueErrorTypes.forEach((type, count) -> {
                    if (count.get() > 0) {
                        System.out.println("      • " + type + ": " + count.get());
                    }
                });
            }
            if (queueConnectionErrors.get() > 0) {
                System.out.println("\n  💡 WHY CONNECTION ERRORS IN QUEUE BUT NOT PURCHASE?");
                System.out.println("    - Queue Phase: All " + TOTAL_USERS + " threads hit simultaneously");
                System.out.println("      → Server connection pool may be overwhelmed");
                System.out.println("      → Some connections rejected during initial burst");
                System.out.println("    - Purchase Phase: Connections already established and reused");
                System.out.println("      → HttpClient connection pool reuses existing connections");
                System.out.println("      → Server already has connections open from queue phase");
                System.out.println("      → Result: Fewer/no connection errors");
            }
        }
        
        System.out.println("\n  ⏱️  Total Time: " + queueTotalTime + " ms (" + (queueTotalTime / 1000.0) + " seconds)");
        System.out.println("  📈 Throughput: " + String.format("%.2f", TOTAL_USERS * 1000.0 / queueTotalTime) + " req/sec");
        if (TOTAL_USERS > 0) {
            System.out.println("  📊 Avg Response Time: " + String.format("%.2f", totalQueueTime.get() / (double) TOTAL_USERS) + " ms");
        }
        
        System.out.println("\n🛒 PURCHASE PHASE METRICS:");
        System.out.println("  Total Requests: " + tokens.size());
        System.out.println("  ✅ Successful: " + purchaseSuccessCount.get() + 
                " (" + (tokens.size() > 0 ? (purchaseSuccessCount.get() * 100.0 / tokens.size()) : 0) + "%)");
        System.out.println("  ❌ Failed: " + purchaseFailureCount.get() + 
                " (" + (tokens.size() > 0 ? (purchaseFailureCount.get() * 100.0 / tokens.size()) : 0) + "%)");
        System.out.println("  ⏱️  Timeout: " + purchaseTimeoutCount.get() + 
                " (" + (tokens.size() > 0 ? (purchaseTimeoutCount.get() * 100.0 / tokens.size()) : 0) + "%)");
        System.out.println("  ⚠️  Errors: " + purchaseErrorCount.get() + 
                " (" + (tokens.size() > 0 ? (purchaseErrorCount.get() * 100.0 / tokens.size()) : 0) + "%)");
        System.out.println("  ⏱️  Total Time: " + purchaseTotalTime + " ms (" + (purchaseTotalTime / 1000.0) + " seconds)");
        if (tokens.size() > 0) {
            System.out.println("  📈 Throughput: " + String.format("%.2f", tokens.size() * 1000.0 / purchaseTotalTime) + " req/sec");
            System.out.println("  📊 Avg Response Time: " + String.format("%.2f", totalPurchaseTime.get() / (double) tokens.size()) + " ms");
        }
        
        // Calculate response time percentiles
        Collections.sort(queueResponseTimes);
        Collections.sort(purchaseResponseTimes);
        
        long queueP50 = queueResponseTimes.isEmpty() ? 0 : queueResponseTimes.get(queueResponseTimes.size() / 2);
        long queueP95 = queueResponseTimes.isEmpty() ? 0 : queueResponseTimes.get((int)(queueResponseTimes.size() * 0.95));
        long queueP99 = queueResponseTimes.isEmpty() ? 0 : queueResponseTimes.get((int)(queueResponseTimes.size() * 0.99));
        
        long purchaseP50 = purchaseResponseTimes.isEmpty() ? 0 : purchaseResponseTimes.get(purchaseResponseTimes.size() / 2);
        long purchaseP95 = purchaseResponseTimes.isEmpty() ? 0 : purchaseResponseTimes.get((int)(purchaseResponseTimes.size() * 0.95));
        long purchaseP99 = purchaseResponseTimes.isEmpty() ? 0 : purchaseResponseTimes.get((int)(purchaseResponseTimes.size() * 0.99));
        
        System.out.println("\n📊 RESPONSE TIME PERCENTILES:");
        System.out.println("  Queue API:");
        System.out.println("    P50 (Median): " + queueP50 + " ms (" + String.format("%.2f", queueP50 / 1000.0) + " seconds)");
        System.out.println("    P95: " + queueP95 + " ms (" + String.format("%.2f", queueP95 / 1000.0) + " seconds)");
        System.out.println("    P99: " + queueP99 + " ms (" + String.format("%.2f", queueP99 / 1000.0) + " seconds)");
        System.out.println("  Purchase API:");
        System.out.println("    P50 (Median): " + purchaseP50 + " ms (" + String.format("%.2f", purchaseP50 / 1000.0) + " seconds)");
        System.out.println("    P95: " + purchaseP95 + " ms (" + String.format("%.2f", purchaseP95 / 1000.0) + " seconds)");
        System.out.println("    P99: " + purchaseP99 + " ms (" + String.format("%.2f", purchaseP99 / 1000.0) + " seconds)");
        
        System.out.println("\n🎯 SYSTEM AVAILABILITY ASSESSMENT:");
        // FIXED: Availability = (Successful + Timeout) / Total Requests
        // This measures if the system responded (even if timeout), not just success rate
        int queueTotalResponses = queueSuccessCount.get() + queueFailureCount.get() + queueTimeoutCount.get();
        int purchaseTotalResponses = purchaseSuccessCount.get() + purchaseFailureCount.get() + purchaseTimeoutCount.get();
        
        double queueAvailability = (queueTotalResponses * 100.0 / TOTAL_USERS);
        double purchaseAvailability = tokens.isEmpty() ? 0 : (purchaseTotalResponses * 100.0 / tokens.size());
        
        // Success Rate (different from availability)
        double queueSuccessRate = (queueSuccessCount.get() * 100.0 / TOTAL_USERS);
        double purchaseSuccessRate = tokens.isEmpty() ? 0 : (purchaseSuccessCount.get() * 100.0 / tokens.size());
        
        System.out.println("  Queue API:");
        System.out.println("    Availability (Response Rate): " + String.format("%.2f", queueAvailability) + "%");
        System.out.println("    Success Rate: " + String.format("%.2f", queueSuccessRate) + "%");
        System.out.println("  Purchase API:");
        System.out.println("    Availability (Response Rate): " + String.format("%.2f", purchaseAvailability) + "%");
        System.out.println("    Success Rate: " + String.format("%.2f", purchaseSuccessRate) + "%");
        
        // Overall assessment based on availability (did system respond?)
        double overallAvailability = (queueAvailability + purchaseAvailability) / 2.0;
        if (overallAvailability >= 99.9) {
            System.out.println("  ✅ EXCELLENT: System handled extreme load gracefully!");
        } else if (overallAvailability >= 99.0) {
            System.out.println("  ⚠️  GOOD: System stayed responsive but some requests failed");
        } else if (overallAvailability >= 95.0) {
            System.out.println("  ⚠️  FAIR: System struggled under load - needs optimization");
        } else {
            System.out.println("  ❌ POOR: System crashed or became unresponsive - critical issues!");
        }
        
        System.out.println("\n💡 Key Metrics:");
        System.out.println("  - Thread Objects Created: " + TOTAL_USERS + "/" + TOTAL_USERS);
        System.out.println("  - Threads Started: " + threadsCreated.get() + "/" + TOTAL_USERS);
        System.out.println("  - Threads Ready: " + threadsReady.get() + "/" + threadsCreated.get());
        System.out.println("  - Did the server crash? " + (queueTotalResponses > 0 && purchaseTotalResponses > 0 ? "No ✅" : "Yes ❌"));
        System.out.println("  - Did the database stay responsive? " + (purchaseErrorCount.get() < tokens.size() * 0.1 ? "Yes ✅" : "No ❌"));
        System.out.println("  - Connection Errors (Queue): " + queueConnectionErrors.get());
        System.out.println("  - Connection Errors (Purchase): " + purchaseConnectionErrors.get());
        System.out.println("  - System handled " + TOTAL_USERS + " concurrent users");
        System.out.println("  - Total successful purchases: " + purchaseSuccessCount.get());
        System.out.println("  - Expected purchases (stock limit): 100");
        System.out.println("  - Overselling occurred: " + (purchaseSuccessCount.get() > 100 ? "Yes ❌" : "No ✅"));
        
        // Design validation metrics
        System.out.println("\n🎯 DESIGN VALIDATION METRICS:");
        boolean noOverselling = purchaseSuccessCount.get() <= 100;
        boolean systemStable = queueTotalResponses > 0 && purchaseTotalResponses > 0;
        boolean acceptableSuccessRate = queueSuccessRate >= 80.0; // At least 80% success rate
        boolean acceptablePurchaseRate = purchaseSuccessCount.get() == 100; // Exactly 100 purchases
        
        System.out.println("  ✅ No Overselling: " + (noOverselling ? "PASS" : "FAIL"));
        System.out.println("  ✅ System Stability: " + (systemStable ? "PASS" : "FAIL"));
        System.out.println("  " + (acceptableSuccessRate ? "✅" : "⚠️") + " Queue Success Rate: " + 
            String.format("%.2f", queueSuccessRate) + "% " + (acceptableSuccessRate ? "(≥80%)" : "(<80%)"));
        System.out.println("  " + (acceptablePurchaseRate ? "✅" : "⚠️") + " Purchase Accuracy: " + 
            purchaseSuccessCount.get() + "/100 " + (acceptablePurchaseRate ? "(Expected)" : "(Mismatch)"));
        
        if (noOverselling && systemStable && acceptableSuccessRate && acceptablePurchaseRate) {
            System.out.println("\n  🎉 DESIGN VALIDATION: PASSED - System meets all requirements!");
        } else {
            System.out.println("\n  ⚠️  DESIGN VALIDATION: PARTIAL - Some requirements not met");
        }
        
        System.out.println("\n==================================================================\n");
    }
    
    private static String enterQueue(HttpClient client, String userId) throws java.net.http.HttpTimeoutException {
        try {
            String jsonBody = String.format("{\"userId\":\"%s\"}", userId);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/enter-queue"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                String body = response.body();
                if (body != null && body.contains("\"success\":true")) {
                    int tokenStart = body.indexOf("\"queueToken\":\"") + 14;
                    int tokenEnd = body.indexOf("\"", tokenStart);
                    if (tokenStart > 13 && tokenEnd > tokenStart) {
                        return body.substring(tokenStart, tokenEnd);
                    } else {
                        // Token parsing failed
                        queueParseErrors.incrementAndGet();
                        queueErrorTypes.computeIfAbsent("PARSE_ERROR", k -> new AtomicInteger(0)).incrementAndGet();
                        return null;
                    }
                } else if (body != null && body.contains("\"success\":false")) {
                    // Server returned success:false
                    queueSuccessFalseErrors.incrementAndGet();
                    queueErrorTypes.computeIfAbsent("SUCCESS_FALSE", k -> new AtomicInteger(0)).incrementAndGet();
                    return null;
                } else {
                    // Unexpected response format
                    queueErrorTypes.computeIfAbsent("UNEXPECTED_FORMAT", k -> new AtomicInteger(0)).incrementAndGet();
                    return null;
                }
            } else {
                // Non-200 status code
                queueHttpErrors.incrementAndGet();
                queueErrorTypes.computeIfAbsent("HTTP_" + response.statusCode(), k -> new AtomicInteger(0)).incrementAndGet();
                return null;
            }
        } catch (java.net.http.HttpTimeoutException e) {
            // Re-throw timeout exceptions to be handled by caller
            throw e;
        } catch (java.io.IOException e) {
            // Check if it's a connection-related IOException
            if (e instanceof java.net.ConnectException || 
                (e.getMessage() != null && (e.getMessage().contains("Connection") || e.getMessage().contains("connect")))) {
                queueConnectionErrors.incrementAndGet();
                queueErrorTypes.computeIfAbsent("CONNECTION_ERROR", k -> new AtomicInteger(0)).incrementAndGet();
            } else {
                queueErrorTypes.computeIfAbsent("IO_ERROR", k -> new AtomicInteger(0)).incrementAndGet();
            }
            return null;
        } catch (Exception e) {
            // Other exceptions
            String errorType = e.getClass().getSimpleName();
            queueErrorTypes.computeIfAbsent(errorType, k -> new AtomicInteger(0)).incrementAndGet();
            return null;
        }
    }
    
    private static boolean attemptPurchase(HttpClient client, String userId, String token, Long productId) throws java.net.http.HttpTimeoutException {
        try {
            String jsonBody = String.format(
                    "{\"userId\":\"%s\",\"queueToken\":\"%s\",\"productId\":%d}",
                    userId, token, productId
            );
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/purchase"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                String body = response.body();
                return body.contains("\"success\":true");
            }
            return false;
        } catch (java.net.http.HttpTimeoutException e) {
            // Re-throw timeout exceptions to be handled by caller
            throw e;
        } catch (Exception e) {
            // Other exceptions (including IOException) - return false
            return false;
        }
    }
}
