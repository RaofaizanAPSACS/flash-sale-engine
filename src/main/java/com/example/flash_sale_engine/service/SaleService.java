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

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SaleService {
    
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    
    // Simple in-memory token store (for Week 1 - will be replaced with Kafka in Week 3)
    private final java.util.Map<String, String> tokenStore = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Fixed implementation: Eliminates race condition by using atomic database operations.
     * 
     * Key improvements:
     * 1. Removed READ-CHECK-SAVE pattern that created race condition window
     * 2. Removed Thread.sleep delay that widened the race condition window
     * 3. Direct atomic decrement - relies on database constraint to prevent negative stock
     * 4. Database constraint (check_stock_non_negative) provides final protection
     * 
     * The atomic SQL update with WHERE stock_quantity > 0 ensures only one thread can
     * successfully decrement when stock is available. The database constraint prevents
     * any possibility of negative stock values.
     */
    /**
     * Optimized purchase flow to minimize database operations and lock contention.
     * 
     * Performance optimizations:
     * 1. Removed unnecessary findById after decrement (was causing extra query + lock)
     * 2. Removed failed order creation (reduces DB writes for failed attempts)
     * 3. Minimal transaction scope - only atomic decrement + order creation
     * 4. Single findById only when needed for successful orders
     */
    @Transactional(timeout = 20) // 5 second timeout to prevent long-running transactions
    public PurchaseResult buyItem(String userId, String queueToken, Long productId) {
        // Validate token (simple check for Week 1) - fast in-memory operation
        if (queueToken == null || !tokenStore.containsKey(queueToken)) {
            return PurchaseResult.failure("Invalid or missing queue token");
        }
        
        // Atomic decrement: This is the ONLY place we check and decrement stock
        // The SQL query ensures: stock_quantity > 0 AND atomic decrement
        // Database constraint ensures: stock_quantity >= 0 (final protection)
        // OPTIMIZED: Direct atomic update without pre-checking product existence
        // The update will fail silently if product doesn't exist (rowsUpdated = 0)
        int rowsUpdated = inventoryRepository.decrementStockNative(productId);
        
        if (rowsUpdated == 0) {
            // No rows updated - stock was already 0, negative, or product doesn't exist
            // Another thread successfully purchased the last item(s), or product invalid
            log.debug("User {} attempted to buy product {} but stock was depleted or product not found", userId, productId);
            
            // OPTIMIZED: Skip failed order creation to reduce DB writes under load
            // Failed attempts are not critical to track in real-time during flash sale
            return PurchaseResult.failure("Sold out - item was just purchased by another user");
        }
        
        // Stock decremented successfully - create confirmed order
        // OPTIMIZED: Load inventory only once, right before creating order
        // This minimizes lock time - we only need it for the order relationship
        Inventory inventory = inventoryRepository.findById(productId)
                .orElseThrow(() -> new IllegalStateException("Product disappeared after successful decrement"));
        
        // Database constraint ensures stock can never be negative, but verify defensively
        if (inventory.getStockQuantity() < 0) {
            log.error("CRITICAL: Database constraint violation! Stock went negative for product {}! Current stock: {}", 
                    productId, inventory.getStockQuantity());
            // This should be impossible with the database constraint
            return PurchaseResult.failure("System error: stock validation failed");
        }
        
        // Create confirmed order - minimal DB operation
        Order order = new Order();
        order.setUserId(userId);
        order.setProduct(inventory);
        order.setStatus(OrderStatus.CONFIRMED);
        order = orderRepository.save(order);
        
        log.info("User {} successfully purchased product {} - Order ID: {} - Stock remaining: {}", 
                userId, productId, order.getId(), inventory.getStockQuantity());
        return PurchaseResult.success(order.getId());
    }
    
    /**
     * Simple token generation for Week 1 (will be replaced with Kafka queue in Week 3)
     */
    public String enterQueue(String userId) {
        String token = UUID.randomUUID().toString();
        tokenStore.put(token, userId);
        log.info("User {} entered queue with token: {}", userId, token);
        return token;
    }
    
    // Inner class for result
    public static class PurchaseResult {
        private final Long orderId;
        private final String message;
        private final boolean success;
        
        private PurchaseResult(Long orderId, String message, boolean success) {
            this.orderId = orderId;
            this.message = message;
            this.success = success;
        }
        
        public static PurchaseResult success(Long orderId) {
            return new PurchaseResult(orderId, "Purchase successful", true);
        }
        
        public static PurchaseResult failure(String message) {
            return new PurchaseResult(null, message, false);
        }
        
        public Long getOrderId() { return orderId; }
        public String getMessage() { return message; }
        public boolean isSuccess() { return success; }
    }
}
