package com.example.flash_sale_engine.worker;

import com.example.flash_sale_engine.model.Inventory;
import com.example.flash_sale_engine.model.Order;
import com.example.flash_sale_engine.model.OrderStatus;
import com.example.flash_sale_engine.repository.InventoryRepository;
import com.example.flash_sale_engine.repository.OrderRepository;
import com.example.flash_sale_engine.service.OrderQueueService;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background worker that consumes orders from Redis Stream and writes to PostgreSQL.
 *
 * Uses TransactionTemplate (programmatic transactions) instead of @Transactional
 * because Spring's proxy-based @Transactional cannot intercept private methods
 * or self-calls within the same class.
 *
 * Only ~100 messages are ever produced (equal to stock quantity), so this worker
 * has minimal load even during a 1M-user flash sale.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderProcessorWorker {

    private static final String CONSUMER_NAME = "worker-1";

    private final RedisTemplate<String, String> redisTemplate;
    private final OrderQueueService orderQueueService;
    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final Counter ordersProcessed;
    private final TransactionTemplate transactionTemplate;

    /**
     * Flag set by DataInitializer after the stream and consumer group are created.
     * Prevents the worker from polling before infrastructure is ready.
     */
    private final AtomicBoolean ready = new AtomicBoolean(false);

    /**
     * Called by DataInitializer after the consumer group is created.
     */
    public void markReady() {
        ready.set(true);
        log.info("OrderProcessorWorker is now ready to consume orders");
    }

    /**
     * Poll Redis Stream every 100ms for new orders.
     * Reads up to 50 messages per batch.
     */
    @SuppressWarnings("unchecked")
    @Scheduled(fixedDelay = 100)
    public void processOrders() {
        if (!ready.get()) {
            return;
        }

        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(OrderQueueService.CONSUMER_GROUP, CONSUMER_NAME),
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                            .count(50)
                            .block(Duration.ofMillis(50)),
                    StreamOffset.create(OrderQueueService.STREAM_KEY, ReadOffset.lastConsumed())
            );

            if (records == null || records.isEmpty()) {
                return;
            }

            for (MapRecord<String, Object, Object> record : records) {
                try {
                    processOrder(record);
                    // ACK the message after successful processing
                    redisTemplate.opsForStream().acknowledge(
                            OrderQueueService.STREAM_KEY,
                            OrderQueueService.CONSUMER_GROUP,
                            record.getId()
                    );
                } catch (Exception e) {
                    log.error("Failed to process order from stream: {}", record.getValue(), e);
                    // Don't ACK - message will be re-delivered
                }
            }
        } catch (Exception e) {
            if (isNoGroupError(e)) {
                log.debug("Stream or consumer group not ready yet, will retry...");
            } else {
                log.error("Error reading from order stream", e);
            }
        }
    }

    /**
     * Process a single order: write to PostgreSQL and update Redis status.
     * Uses programmatic TransactionTemplate to ensure a real DB transaction exists.
     */
    private void processOrder(MapRecord<String, Object, Object> record) {
        Map<Object, Object> data = record.getValue();

        // Skip seed/init records created during ensureConsumerGroup()
        if (data.containsKey("_seed") || data.containsKey("init")) {
            return;
        }

        String orderId = String.valueOf(data.get("orderId"));
        String userId = String.valueOf(data.get("userId"));
        Long productId = Long.parseLong(String.valueOf(data.get("productId")));

        log.info("Processing order: orderId={}, userId={}, productId={}", orderId, userId, productId);

        try {
            transactionTemplate.executeWithoutResult(status -> {
                // Atomic decrement in PostgreSQL as the final source of truth
                int rowsUpdated = inventoryRepository.decrementStockNative(productId);

                if (rowsUpdated == 0) {
                    log.error("DB stock depleted but Redis allowed purchase: orderId={}, productId={}", orderId, productId);
                    orderQueueService.updateOrderStatus(orderId, "FAILED");
                    return;
                }

                // Load inventory for the order relationship
                Inventory inventory = inventoryRepository.findById(productId)
                        .orElseThrow(() -> new IllegalStateException("Product not found: " + productId));

                // Create confirmed order in PostgreSQL
                Order order = new Order();
                order.setUserId(userId);
                order.setProduct(inventory);
                order.setStatus(OrderStatus.CONFIRMED);
                order = orderRepository.save(order);

                // Update Redis with confirmed status and DB order ID
                orderQueueService.updateOrderStatus(orderId, "CONFIRMED");
                orderQueueService.storeDbOrderId(orderId, order.getId());
                ordersProcessed.increment();

                log.info("Order confirmed in DB: orderId={}, dbOrderId={}, userId={}, stockRemaining={}",
                        orderId, order.getId(), userId, inventory.getStockQuantity());
            });
        } catch (Exception e) {
            log.error("Failed to persist order: orderId={}", orderId, e);
            orderQueueService.updateOrderStatus(orderId, "FAILED");
            throw e;
        }
    }

    private boolean isNoGroupError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && (msg.contains("NOGROUP") || msg.contains("no such key"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
