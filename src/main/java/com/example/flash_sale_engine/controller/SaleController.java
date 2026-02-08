package com.example.flash_sale_engine.controller;

import com.example.flash_sale_engine.model.Inventory;
import com.example.flash_sale_engine.service.OrderQueueService;
import com.example.flash_sale_engine.service.SaleLifecycleService;
import com.example.flash_sale_engine.service.SaleService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
@Slf4j
public class SaleController {

    private final SaleService saleService;
    private final OrderQueueService orderQueueService;
    private final SaleLifecycleService saleLifecycleService;

    // Micrometer metrics
    private final Counter requestsTotal;
    private final Counter requestsSoldOut;
    private final Counter purchasesSuccess;
    private final Timer purchaseLatency;

    /**
     * GET /api/v2/products
     * List all products with live stock count from Redis.
     */
    @GetMapping("/products")
    public ResponseEntity<?> getProducts() {
        try {
            List<Inventory> products = saleService.getAllProducts();
            List<Map<String, Object>> result = products.stream().map(p -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", p.getId());
                map.put("productName", p.getProductName());
                map.put("stockQuantity", saleService.getLiveStock(p.getId()));
                return map;
            }).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error fetching products", e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "An error occurred while loading products.",
                    "error", "INTERNAL_ERROR"
            ));
        }
    }

    /**
     * GET /api/v2/products/{id}
     * Get a single product with live stock from Redis.
     */
    @GetMapping("/products/{id}")
    public ResponseEntity<?> getProduct(@PathVariable Long id) {
        try {
            Inventory product = saleService.getProduct(id);
            if (product == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "Product not found",
                        "error", "PRODUCT_NOT_FOUND"
                ));
            }
            Map<String, Object> result = new HashMap<>();
            result.put("id", product.getId());
            result.put("productName", product.getProductName());
            result.put("stockQuantity", saleService.getLiveStock(id));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error fetching product: id={}", id, e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "An error occurred while loading the product.",
                    "error", "INTERNAL_ERROR"
            ));
        }
    }

    /**
     * POST /api/v2/products/{id}/purchase
     * Purchase a product.
     *
     * Hot path: Rate Limit (interceptor) -> Redis stock check -> Redis Stream enqueue -> 202 Accepted
     * No PostgreSQL on the hot path.
     *
     * Request body: { "userId": "user123" }
     *
     * Response codes:
     * - 202: Order accepted (being processed asynchronously)
     * - 400: Bad request (missing userId)
     * - 404: Product not found
     * - 409: Sold out
     * - 425: Sale not started yet
     * - 429: Rate limited (handled by interceptor)
     * - 500: Internal server error
     */
    @PostMapping("/products/{id}/purchase")
    public ResponseEntity<?> purchase(@PathVariable Long id, @RequestBody Map<String, String> body) {
        requestsTotal.increment();

        String userId = body.get("userId");
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "userId is required",
                    "success", false,
                    "error", "MISSING_USER_ID"
            ));
        }

        try {
            return purchaseLatency.record(() -> {
                SaleService.PurchaseResult result = saleService.purchaseProduct(userId, id);

                if (result.isSuccess()) {
                    purchasesSuccess.increment();
                    Map<String, Object> response = new HashMap<>();
                    response.put("orderId", result.getOrderId());
                    response.put("message", result.getMessage());
                    response.put("success", true);
                    response.put("stockRemaining", result.getStockRemaining());
                    return ResponseEntity.accepted().body(response);
                }

                if (result.isSoldOut()) {
                    requestsSoldOut.increment();
                    return ResponseEntity.status(409).body(Map.of(
                            "message", result.getMessage(),
                            "success", false,
                            "error", "SOLD_OUT"
                    ));
                }

                if ("SALE_NOT_STARTED".equals(result.getError())) {
                    return ResponseEntity.status(425).body(Map.of(
                            "message", result.getMessage(),
                            "success", false,
                            "error", "SALE_NOT_STARTED"
                    ));
                }

                if ("PRODUCT_NOT_FOUND".equals(result.getError())) {
                    return ResponseEntity.status(404).body(Map.of(
                            "message", result.getMessage(),
                            "success", false,
                            "error", "PRODUCT_NOT_FOUND"
                    ));
                }

                return ResponseEntity.status(400).body(Map.of(
                        "message", result.getMessage(),
                        "success", false,
                        "error", result.getError() != null ? result.getError() : "UNKNOWN"
                ));
            });

        } catch (Exception e) {
            log.error("Unexpected error processing purchase: user={}, product={}", userId, id, e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "An unexpected error occurred. Please try again later.",
                    "success", false,
                    "error", "INTERNAL_ERROR"
            ));
        }
    }

    /**
     * GET /api/v2/orders/{orderId}/status
     * Check order processing status.
     */
    @GetMapping("/orders/{orderId}/status")
    public ResponseEntity<?> getOrderStatus(@PathVariable String orderId) {
        try {
            String status = orderQueueService.getOrderStatus(orderId);
            if (status == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "Order not found",
                        "error", "ORDER_NOT_FOUND"
                ));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("orderId", orderId);
            response.put("status", status);

            Long dbOrderId = orderQueueService.getDbOrderId(orderId);
            if (dbOrderId != null) {
                response.put("dbOrderId", dbOrderId);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching order status: orderId={}", orderId, e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "An error occurred while checking order status.",
                    "error", "INTERNAL_ERROR"
            ));
        }
    }

    /**
     * GET /api/v2/sale/status
     * Get current sale status for a product.
     */
    @GetMapping("/sale/status")
    public ResponseEntity<?> getSaleStatus(@RequestParam(defaultValue = "1") Long productId) {
        try {
            SaleLifecycleService.SaleStatus status = saleLifecycleService.getSaleStatus(productId);
            int stock = saleService.getLiveStock(productId);

            return ResponseEntity.ok(Map.of(
                    "productId", productId,
                    "saleStatus", status.name(),
                    "stockRemaining", Math.max(stock, 0)
            ));
        } catch (Exception e) {
            log.error("Error fetching sale status", e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "An error occurred while checking sale status.",
                    "error", "INTERNAL_ERROR"
            ));
        }
    }
}
