package com.example.flash_sale_engine.service;

import com.example.flash_sale_engine.model.Inventory;
import com.example.flash_sale_engine.repository.InventoryRepository;
import com.example.flash_sale_engine.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Core sale service for v2 optimized flash sale engine.
 *
 * Purchase flow (hot path):
 * 1. Rate limit check (RateLimiterService - handled by interceptor)
 * 2. Sale status check (SaleLifecycleService)
 * 3. Atomic stock decrement (StockCacheService - Redis Lua)
 * 4. Enqueue order (OrderQueueService - Redis Stream)
 * 5. Return 202 Accepted immediately
 *
 * The hot path NEVER touches PostgreSQL. Database writes happen
 * asynchronously via the OrderProcessorWorker.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SaleService {

    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final StockCacheService stockCacheService;
    private final OrderQueueService orderQueueService;
    private final SaleLifecycleService saleLifecycleService;

    // --- Product browsing (reads from DB, stock from Redis) ---

    @Transactional(readOnly = true)
    public List<Inventory> getAllProducts() {
        return inventoryRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Inventory getProduct(Long productId) {
        return inventoryRepository.findById(productId).orElse(null);
    }

    /**
     * Get live stock count from Redis (sub-millisecond).
     */
    public int getLiveStock(Long productId) {
        return stockCacheService.getStock(productId);
    }

    // --- Purchase flow (hot path - Redis only) ---

    /**
     * Attempt to purchase a product.
     *
     * This is the optimized hot path:
     * 1. Check sale status (Redis) - instant reject if UPCOMING or SOLD_OUT
     * 2. Atomic stock decrement (Redis Lua) - instant reject if stock = 0
     * 3. Enqueue order (Redis Stream) - async DB write
     * 4. Return order reference immediately
     *
     * Total latency: ~1-5ms (all Redis, no PostgreSQL)
     *
     * Lua script return values:
     *   -1  = product not found in Redis
     *   -2  = sold out (stock was already 0, no decrement)
     *   >= 0 = remaining stock after successful decrement
     */
    public PurchaseResult purchaseProduct(String userId, Long productId) {
        // 0. In-memory fast path: if already sold out, reject instantly (no Redis calls at all)
        if (saleLifecycleService.isSoldOutInMemory(productId)) {
            return PurchaseResult.soldOut();
        }

        // 1. Check sale status (Redis lookup, only during the brief active window)
        SaleLifecycleService.SaleStatus status = saleLifecycleService.getSaleStatus(productId);
        if (status == SaleLifecycleService.SaleStatus.UPCOMING) {
            return PurchaseResult.failure("Sale has not started yet", "SALE_NOT_STARTED");
        }
        if (status == SaleLifecycleService.SaleStatus.SOLD_OUT) {
            return PurchaseResult.soldOut();
        }

        // 2. Atomic stock decrement in Redis (Lua script)
        long remaining = stockCacheService.tryDecrementStock(productId);

        if (remaining == -1) {
            return PurchaseResult.failure("Product not found", "PRODUCT_NOT_FOUND");
        }

        if (remaining == -2) {
            // Stock was already 0 - mark sale as sold out for future fast-path
            saleLifecycleService.markSoldOut(productId);
            return PurchaseResult.soldOut();
        }

        // remaining >= 0: stock was successfully decremented
        // If remaining == 0, this user got the last item
        if (remaining == 0) {
            saleLifecycleService.markSoldOut(productId);
        }

        // 3. Enqueue order for async DB write
        String orderId = orderQueueService.enqueueOrder(userId, productId);

        log.info("Purchase accepted: userId={}, productId={}, orderId={}, stockRemaining={}",
                userId, productId, orderId, remaining);

        return PurchaseResult.accepted(orderId, (int) remaining);
    }

    // --- Result wrapper ---

    public static class PurchaseResult {
        private final String orderId;
        private final String message;
        private final boolean success;
        private final Integer stockRemaining;
        private final String error;
        private final boolean soldOut;

        private PurchaseResult(String orderId, String message, boolean success,
                               Integer stockRemaining, String error, boolean soldOut) {
            this.orderId = orderId;
            this.message = message;
            this.success = success;
            this.stockRemaining = stockRemaining;
            this.error = error;
            this.soldOut = soldOut;
        }

        public static PurchaseResult accepted(String orderId, Integer stockRemaining) {
            return new PurchaseResult(orderId, "Order accepted and being processed",
                    true, stockRemaining, null, false);
        }

        public static PurchaseResult soldOut() {
            return new PurchaseResult(null, "Sorry, this product is sold out.",
                    false, 0, "SOLD_OUT", true);
        }

        public static PurchaseResult failure(String message, String error) {
            return new PurchaseResult(null, message, false, null, error, false);
        }

        public String getOrderId() { return orderId; }
        public String getMessage() { return message; }
        public boolean isSuccess() { return success; }
        public Integer getStockRemaining() { return stockRemaining; }
        public String getError() { return error; }
        public boolean isSoldOut() { return soldOut; }
    }
}
