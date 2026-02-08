package com.example.flash_sale_engine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Enqueues purchase orders into a Redis Stream for async processing.
 *
 * When a user successfully claims stock (Redis Lua decrement), the order
 * is added to a Redis Stream. A background worker consumes and writes to PostgreSQL.
 *
 * This decouples the fast path (user gets immediate response) from the slow path
 * (database write). The user gets a 202 Accepted with an order ID they can poll.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderQueueService {

    public static final String STREAM_KEY = "orders:pending";
    public static final String CONSUMER_GROUP = "order-processors";
    private static final String ORDER_STATUS_PREFIX = "order:status:";
    private static final String ORDER_DBID_PREFIX = "order:dbid:";

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Enqueue an order for async processing.
     *
     * @return order reference ID (UUID) that the client can use to check status
     */
    public String enqueueOrder(String userId, Long productId) {
        String orderId = UUID.randomUUID().toString();

        // Store initial order status in Redis
        String statusKey = ORDER_STATUS_PREFIX + orderId;
        redisTemplate.opsForValue().set(statusKey, "PENDING");
        redisTemplate.expire(statusKey, 1, TimeUnit.HOURS);

        // Add to Redis Stream
        Map<String, String> orderData = new HashMap<>();
        orderData.put("orderId", orderId);
        orderData.put("userId", userId);
        orderData.put("productId", String.valueOf(productId));
        orderData.put("timestamp", String.valueOf(System.currentTimeMillis()));

        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(STREAM_KEY)
                .ofMap(orderData);

        redisTemplate.opsForStream().add(record);

        log.info("Enqueued order: orderId={}, userId={}, productId={}", orderId, userId, productId);
        return orderId;
    }

    /**
     * Get order status from Redis (fast lookup).
     */
    public String getOrderStatus(String orderId) {
        String statusKey = ORDER_STATUS_PREFIX + orderId;
        return redisTemplate.opsForValue().get(statusKey);
    }

    /**
     * Update order status in Redis (called by the worker after DB write).
     */
    public void updateOrderStatus(String orderId, String status) {
        String statusKey = ORDER_STATUS_PREFIX + orderId;
        redisTemplate.opsForValue().set(statusKey, status);
        redisTemplate.expire(statusKey, 1, TimeUnit.HOURS);
    }

    /**
     * Store the database-generated order ID so the client can retrieve it.
     */
    public void storeDbOrderId(String orderId, Long dbOrderId) {
        String dbIdKey = ORDER_DBID_PREFIX + orderId;
        redisTemplate.opsForValue().set(dbIdKey, String.valueOf(dbOrderId));
        redisTemplate.expire(dbIdKey, 1, TimeUnit.HOURS);
    }

    /**
     * Get the database-generated order ID.
     */
    public Long getDbOrderId(String orderId) {
        String dbIdKey = ORDER_DBID_PREFIX + orderId;
        String value = redisTemplate.opsForValue().get(dbIdKey);
        return value != null ? Long.parseLong(value) : null;
    }

    /**
     * Ensure the consumer group exists for the stream.
     * Called on startup.
     */
    public void ensureConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, CONSUMER_GROUP);
            log.info("Created Redis Stream consumer group: {}", CONSUMER_GROUP);
        } catch (Exception e) {
            if (isBusyGroupError(e)) {
                // Consumer group already exists from a previous run - this is fine
                log.info("Consumer group already exists: {}", CONSUMER_GROUP);
            } else {
                // Stream key doesn't exist yet - create it with a seed entry, then create the group
                try {
                    Map<String, String> seed = Map.of("_seed", "1");
                    MapRecord<String, String, String> record = StreamRecords.newRecord()
                            .in(STREAM_KEY)
                            .ofMap(seed);
                    redisTemplate.opsForStream().add(record);
                    redisTemplate.opsForStream().createGroup(STREAM_KEY, CONSUMER_GROUP);
                    log.info("Created Redis Stream and consumer group: {}", CONSUMER_GROUP);
                } catch (Exception ex) {
                    if (isBusyGroupError(ex)) {
                        log.info("Consumer group already exists: {}", CONSUMER_GROUP);
                    } else {
                        log.warn("Could not create consumer group: {}", ex.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Check if the exception (or any cause) is a BUSYGROUP error
     * (consumer group already exists). RedisSystemException wraps the
     * actual RedisCommandExecutionException, so we walk the cause chain.
     */
    private boolean isBusyGroupError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
