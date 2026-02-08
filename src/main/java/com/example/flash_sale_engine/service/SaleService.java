package com.example.flash_sale_engine.service;

import com.example.flash_sale_engine.model.Inventory;
import com.example.flash_sale_engine.model.Order;
import com.example.flash_sale_engine.model.OrderStatus;
import com.example.flash_sale_engine.repository.InventoryRepository;
import com.example.flash_sale_engine.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SaleService {

    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;

    /**
     * Get all products with their current stock.
     */
    @Transactional(readOnly = true)
    public List<Inventory> getAllProducts() {
        return inventoryRepository.findAll();
    }

    /**
     * Get a single product by ID.
     */
    @Transactional(readOnly = true)
    public Inventory getProduct(Long productId) {
        return inventoryRepository.findById(productId).orElse(null);
    }

    /**
     * Purchase a product. This is the core operation.
     *
     * How it works:
     * 1. Atomic SQL: UPDATE inventory SET stock_quantity = stock_quantity - 1
     *    WHERE id = :id AND stock_quantity > 0
     * 2. If rowsUpdated == 0 → sold out (stock was already 0)
     * 3. If rowsUpdated == 1 → stock decremented, create order
     *
     * Why this is correct:
     * - The WHERE clause + atomic UPDATE means only one thread can claim the last item
     * - The database CHECK constraint (stock_quantity >= 0) is a safety net
     * - No read-before-write race condition
     *
     * Why this won't handle peak concurrent load:
     * - All requests serialize on the same row lock
     * - Connection pool becomes the bottleneck under thousands of requests
     * - No caching, no message queue, no rate limiting
     * - This is fine for normal traffic, not for flash sale spikes
     *
     * Timeout: 5 seconds - prevents long-running transactions from blocking others
     */
    @Transactional(timeout = 5)
    public PurchaseResult purchaseProduct(String userId, Long productId) {
        // Atomic decrement - single SQL statement, no race condition
        int rowsUpdated = inventoryRepository.decrementStockNative(productId);

        if (rowsUpdated == 0) {
            // Either product doesn't exist or stock is 0
            boolean exists = inventoryRepository.existsById(productId);
            if (!exists) {
                return PurchaseResult.failure("Product not found");
            }
            return PurchaseResult.failure("Sold out");
        }

        // Stock decremented successfully - create order
        Inventory inventory = inventoryRepository.findById(productId)
                .orElseThrow(() -> new IllegalStateException("Product disappeared after stock decrement"));

        Order order = new Order();
        order.setUserId(userId);
        order.setProduct(inventory);
        order.setStatus(OrderStatus.CONFIRMED);
        order = orderRepository.save(order);

        log.info("Purchase successful: user={}, product={}, orderId={}, stockRemaining={}",
                userId, productId, order.getId(), inventory.getStockQuantity());

        return PurchaseResult.success(order.getId(), inventory.getStockQuantity());
    }

    // Result wrapper
    public static class PurchaseResult {
        private final Long orderId;
        private final String message;
        private final boolean success;
        private final Integer stockRemaining;

        private PurchaseResult(Long orderId, String message, boolean success, Integer stockRemaining) {
            this.orderId = orderId;
            this.message = message;
            this.success = success;
            this.stockRemaining = stockRemaining;
        }

        public static PurchaseResult success(Long orderId, Integer stockRemaining) {
            return new PurchaseResult(orderId, "Purchase successful", true, stockRemaining);
        }

        public static PurchaseResult failure(String message) {
            return new PurchaseResult(null, message, false, null);
        }

        public Long getOrderId() { return orderId; }
        public String getMessage() { return message; }
        public boolean isSuccess() { return success; }
        public Integer getStockRemaining() { return stockRemaining; }
    }
}
