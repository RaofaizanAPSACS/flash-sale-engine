package com.example.flash_sale_engine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the lifecycle of a flash sale with in-memory fast path.
 *
 * Sale states:
 *   UPCOMING -> ACTIVE -> SOLD_OUT
 *
 * Performance optimization:
 *   Once a product is SOLD_OUT, the status is cached in-memory.
 *   Subsequent requests skip ALL Redis calls and reject instantly
 *   from memory (~microseconds vs ~milliseconds per Redis call).
 *
 *   For a sale with 100 stock and 168k total requests:
 *   - First ~100 requests: Redis calls (normal path)
 *   - Remaining ~167,900 requests: ZERO Redis calls (memory fast path)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SaleLifecycleService {

    private static final String SALE_STATUS_PREFIX = "sale:status:";

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * In-memory cache for sold-out products.
     * Once a product transitions to SOLD_OUT, it never goes back to ACTIVE
     * (within a single sale), so this cache is safe to use without invalidation.
     */
    private final ConcurrentHashMap<Long, SaleStatus> statusCache = new ConcurrentHashMap<>();

    /**
     * Global flag: at least one product is sold out.
     * Used by the rate-limit interceptor to skip Redis rate-limit calls entirely.
     */
    private final AtomicBoolean anySoldOut = new AtomicBoolean(false);

    public enum SaleStatus {
        UPCOMING, ACTIVE, SOLD_OUT
    }

    /**
     * Start a sale for a product. Sets status to ACTIVE.
     */
    public void startSale(Long productId) {
        String key = SALE_STATUS_PREFIX + productId;
        redisTemplate.opsForValue().set(key, SaleStatus.ACTIVE.name());
        statusCache.put(productId, SaleStatus.ACTIVE);
        log.info("Sale started for product: {}", productId);
    }

    /**
     * Mark a sale as sold out. Called when Redis stock hits 0.
     * Also sets the in-memory flag for instant future rejections.
     */
    public void markSoldOut(Long productId) {
        String key = SALE_STATUS_PREFIX + productId;
        redisTemplate.opsForValue().set(key, SaleStatus.SOLD_OUT.name());
        statusCache.put(productId, SaleStatus.SOLD_OUT);
        anySoldOut.set(true);
        log.info("Sale sold out for product: {}", productId);
    }

    /**
     * Get current sale status with in-memory fast path.
     *
     * If the product is already SOLD_OUT in memory, returns immediately
     * without any Redis call. This eliminates Redis overhead for 99%+ of
     * requests once stock is depleted.
     */
    public SaleStatus getSaleStatus(Long productId) {
        // In-memory fast path: SOLD_OUT is terminal and never reverts
        SaleStatus cached = statusCache.get(productId);
        if (cached == SaleStatus.SOLD_OUT) {
            return SaleStatus.SOLD_OUT;
        }

        // For ACTIVE/UPCOMING, check Redis (only needed during the brief window
        // when stock is still available, i.e., first ~100 requests)
        String key = SALE_STATUS_PREFIX + productId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return SaleStatus.UPCOMING;
        }
        try {
            SaleStatus status = SaleStatus.valueOf(value);
            statusCache.put(productId, status);
            return status;
        } catch (IllegalArgumentException e) {
            return SaleStatus.UPCOMING;
        }
    }

    /**
     * Check if any product is sold out (in-memory, no Redis call).
     * Used by the rate-limit interceptor to skip rate limiting entirely
     * when we know requests will be rejected anyway.
     */
    public boolean isAnySoldOut() {
        return anySoldOut.get();
    }

    /**
     * Check if a specific product is sold out (in-memory only, no Redis call).
     */
    public boolean isSoldOutInMemory(Long productId) {
        return statusCache.get(productId) == SaleStatus.SOLD_OUT;
    }

    /**
     * Reset sale status (for testing / sale restart).
     */
    public void resetSale(Long productId) {
        String key = SALE_STATUS_PREFIX + productId;
        redisTemplate.delete(key);
        statusCache.remove(productId);
        anySoldOut.set(false);
        log.info("Sale reset for product: {}", productId);
    }
}
