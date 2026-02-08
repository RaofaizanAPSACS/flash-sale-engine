package com.example.flash_sale_engine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Redis-based stock cache for sub-millisecond stock operations.
 *
 * This is the core optimization: instead of hitting PostgreSQL for every request,
 * stock is managed in Redis using atomic Lua scripts. PostgreSQL only receives
 * writes for successful purchases (~100 for 100 stock).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockCacheService {

    private static final String STOCK_KEY_PREFIX = "stock:";

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> decrementStockScript;

    /**
     * Initialize stock in Redis from database value.
     * Called on application startup and when a sale is started.
     */
    public void initializeStock(Long productId, int quantity) {
        String key = STOCK_KEY_PREFIX + productId;
        redisTemplate.opsForValue().set(key, String.valueOf(quantity));
        log.info("Initialized Redis stock: product={}, quantity={}", productId, quantity);
    }

    /**
     * Atomically check and decrement stock using Lua script.
     *
     * Returns:
     *   >= 0: remaining stock after decrement (success)
     *   0: sold out (stock was already 0)
     *   -1: product not found in cache
     *
     * This is atomic - Redis executes Lua scripts single-threaded.
     * No race conditions are possible.
     */
    public long tryDecrementStock(Long productId) {
        String key = STOCK_KEY_PREFIX + productId;
        Long result = redisTemplate.execute(decrementStockScript, Collections.singletonList(key));
        return result != null ? result : -1;
    }

    /**
     * Get current stock from Redis (for product pages).
     */
    public int getStock(Long productId) {
        String key = STOCK_KEY_PREFIX + productId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) return -1;
        return Integer.parseInt(value);
    }

    /**
     * Reset stock in Redis (for testing / sale restart).
     */
    public void resetStock(Long productId, int quantity) {
        initializeStock(productId, quantity);
        log.info("Reset Redis stock: product={}, quantity={}", productId, quantity);
    }
}
