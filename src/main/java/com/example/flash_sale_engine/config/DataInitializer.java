package com.example.flash_sale_engine.config;

import com.example.flash_sale_engine.model.Inventory;
import com.example.flash_sale_engine.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Initializes test data on application startup.
 * Creates a product with 100 items in stock for stress testing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {
    
    private final InventoryRepository inventoryRepository;
    
    @Override
    public void run(String... args) {
        // Check if product already exists
        if (inventoryRepository.findByProductName("Flash Sale Product").isEmpty()) {
            Inventory product = new Inventory();
            product.setProductName("Flash Sale Product");
            product.setStockQuantity(100); // Start with 100 items
            product.setVersion(0);
            
            inventoryRepository.save(product);
            log.info("✅ Initialized test product: {} with stock: {}", 
                    product.getProductName(), product.getStockQuantity());
            log.info("📝 Product ID: {}", product.getId());
        } else {
            log.info("ℹ️  Test product already exists. Skipping initialization.");
        }
    }
}
