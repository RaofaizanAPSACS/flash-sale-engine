package com.example.flash_sale_engine.controller;

import com.example.flash_sale_engine.dto.*;
import com.example.flash_sale_engine.service.SaleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sale")
@RequiredArgsConstructor
@Slf4j
public class SaleController {
    
    private final SaleService saleService;
    
    /**
     * POST /api/v1/sale/enter-queue
     * User requests to join the sale.
     * Returns a queue_token or a "Come back later" message.
     * 
     * Designed to handle millions of concurrent requests without crashing.
     */
    @PostMapping("/enter-queue")
    public ResponseEntity<EnterQueueResponse> enterQueue(@RequestBody EnterQueueRequest request) {
        long startTime = System.currentTimeMillis();
        try {
            String token = saleService.enterQueue(request.getUserId());
            long duration = System.currentTimeMillis() - startTime;
            
            if (token != null) {
                return ResponseEntity.ok(new EnterQueueResponse(token, "Successfully entered queue", true));
            } else {
                return ResponseEntity.ok(new EnterQueueResponse(null, "Failed to enter queue. Please try again later.", false));
            }
        } catch (org.springframework.dao.QueryTimeoutException | org.springframework.dao.DataAccessResourceFailureException e) {
            log.warn("Database timeout/connection issue for user: {} - {}", request.getUserId(), e.getMessage());
            return ResponseEntity.status(503).body(new EnterQueueResponse(null, "Service temporarily unavailable. Please try again.", false));
        } catch (Exception e) {
            log.error("Error entering queue for user: {}", request.getUserId(), e);
            return ResponseEntity.status(500).body(new EnterQueueResponse(null, "Failed to enter queue. Please try again later.", false));
        }
    }
    
    /**
     * POST /api/v1/sale/purchase
     * User attempts to buy the item.
     * Must have a valid queue_token.
     * 
     * Designed to handle extreme load while maintaining data consistency.
     * Under high load, some requests may timeout or fail, but the system should stay responsive.
     */
    @PostMapping("/purchase")
    public ResponseEntity<PurchaseResponse> purchase(@RequestBody PurchaseRequest request) {
        long startTime = System.currentTimeMillis();
        try {
            SaleService.PurchaseResult result = saleService.buyItem(
                request.getUserId(),
                request.getQueueToken(),
                request.getProductId()
            );
            
            long duration = System.currentTimeMillis() - startTime;
            
            if (result.isSuccess()) {
                return ResponseEntity.ok(new PurchaseResponse(
                    result.getOrderId(),
                    result.getMessage(),
                    true
                ));
            } else {
                return ResponseEntity.ok(new PurchaseResponse(
                    null,
                    result.getMessage(),
                    false
                ));
            }
        } catch (org.springframework.dao.QueryTimeoutException | org.springframework.dao.DataAccessResourceFailureException e) {
            log.warn("Database timeout/connection issue for user: {} - {}", request.getUserId(), e.getMessage());
            return ResponseEntity.status(503).body(new PurchaseResponse(
                null,
                "Service temporarily unavailable. Please try again.",
                false
            ));
        } catch (org.springframework.transaction.TransactionTimedOutException e) {
            log.warn("Transaction timeout for user: {}", request.getUserId());
            return ResponseEntity.status(504).body(new PurchaseResponse(
                null,
                "Request timed out. Please try again.",
                false
            ));
        } catch (Exception e) {
            log.error("Error processing purchase for user: {}", request.getUserId(), e);
            return ResponseEntity.status(500).body(new PurchaseResponse(
                null,
                "Purchase failed due to system error",
                false
            ));
        }
    }
}
