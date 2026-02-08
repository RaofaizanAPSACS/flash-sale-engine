package com.example.flash_sale_engine.controller;

import com.example.flash_sale_engine.model.Inventory;
import com.example.flash_sale_engine.service.SaleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class SaleController {

    private final SaleService saleService;

    /**
     * GET /api/v1/products
     * List all products with current stock.
     */
    @GetMapping("/products")
    public ResponseEntity<?> getProducts() {
        try {
            return ResponseEntity.ok(saleService.getAllProducts());
        } catch (org.springframework.dao.QueryTimeoutException | org.springframework.dao.DataAccessResourceFailureException e) {
            log.warn("Database timeout/connection issue while fetching products", e);
            return ResponseEntity.status(503).body(Map.of(
                    "message", "Unable to load products. Please try again in a moment.",
                    "error", "SERVICE_UNAVAILABLE"
            ));
        } catch (Exception e) {
            log.error("Error fetching products", e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "An error occurred while loading products.",
                    "error", "INTERNAL_ERROR"
            ));
        }
    }

    /**
     * GET /api/v1/products/{id}
     * Get a single product.
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
            return ResponseEntity.ok(product);
        } catch (org.springframework.dao.QueryTimeoutException | org.springframework.dao.DataAccessResourceFailureException e) {
            log.warn("Database timeout/connection issue while fetching product: id={}", id, e);
            return ResponseEntity.status(503).body(Map.of(
                    "message", "Unable to load product. Please try again in a moment.",
                    "error", "SERVICE_UNAVAILABLE"
            ));
        } catch (Exception e) {
            log.error("Error fetching product: id={}", id, e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "An error occurred while loading the product.",
                    "error", "INTERNAL_ERROR"
            ));
        }
    }

    /**
     * POST /api/v1/products/{id}/purchase
     * Purchase a product. Simple: user visits page, clicks buy, done.
     *
     * Request body: { "userId": "user123" }
     * 
     * Response codes:
     * - 200: Purchase successful
     * - 400: Bad request (missing userId)
     * - 404: Product not found
     * - 409: Sold out
     * - 503: Service unavailable (database timeout/connection issue)
     * - 504: Request timeout (transaction took too long)
     * - 500: Internal server error
     */
    @PostMapping("/products/{id}/purchase")
    public ResponseEntity<?> purchase(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "userId is required",
                    "success", false,
                    "error", "MISSING_USER_ID"
            ));
        }

        try {
            SaleService.PurchaseResult result = saleService.purchaseProduct(userId, id);

            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                        "orderId", result.getOrderId(),
                        "message", result.getMessage(),
                        "success", true,
                        "stockRemaining", result.getStockRemaining()
                ));
            }

            // Product not found
            if (result.getMessage().equals("Product not found")) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "Product not found",
                        "success", false,
                        "error", "PRODUCT_NOT_FOUND"
                ));
            }

            // Sold out - 409 Conflict
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Sorry, this product is sold out. Please try another product.",
                    "success", false,
                    "error", "SOLD_OUT"
            ));
            
        } catch (org.springframework.transaction.TransactionTimedOutException e) {
            log.warn("Transaction timeout for purchase: user={}, product={}", userId, id);
            return ResponseEntity.status(504).body(Map.of(
                    "message", "Your request took too long to process. The system is experiencing high load. Please try again in a moment.",
                    "success", false,
                    "error", "REQUEST_TIMEOUT"
            ));
            
        } catch (org.springframework.dao.QueryTimeoutException e) {
            log.warn("Database query timeout for purchase: user={}, product={}", userId, id);
            return ResponseEntity.status(503).body(Map.of(
                    "message", "The system is currently experiencing high demand. Please try again in a few moments.",
                    "success", false,
                    "error", "DATABASE_TIMEOUT"
            ));
            
        } catch (org.springframework.dao.DataAccessResourceFailureException e) {
            log.error("Database connection failure for purchase: user={}, product={}", userId, id, e);
            return ResponseEntity.status(503).body(Map.of(
                    "message", "Unable to connect to the database. Please try again later.",
                    "success", false,
                    "error", "DATABASE_UNAVAILABLE"
            ));
            
        } catch (org.springframework.dao.DataAccessException e) {
            log.error("Database error for purchase: user={}, product={}", userId, id, e);
            return ResponseEntity.status(503).body(Map.of(
                    "message", "A database error occurred. Please try again later.",
                    "success", false,
                    "error", "DATABASE_ERROR"
            ));
            
        } catch (Exception e) {
            log.error("Unexpected error processing purchase: user={}, product={}", userId, id, e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "An unexpected error occurred. Please try again later.",
                    "success", false,
                    "error", "INTERNAL_ERROR"
            ));
        }
    }
}
