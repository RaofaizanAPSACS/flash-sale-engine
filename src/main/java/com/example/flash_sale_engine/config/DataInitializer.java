package com.example.flash_sale_engine.config;

import com.example.flash_sale_engine.model.Inventory;
import com.example.flash_sale_engine.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final InventoryRepository inventoryRepository;

    @Override
    public void run(String... args) {
        if (inventoryRepository.findByProductName("Flash Sale Product").isEmpty()) {
            Inventory product = new Inventory();
            product.setProductName("Flash Sale Product");
            product.setStockQuantity(100);
            product.setVersion(0);
            inventoryRepository.save(product);
            log.info("Initialized product: '{}' with stock: {} (id={})",
                    product.getProductName(), product.getStockQuantity(), product.getId());
        } else {
            log.info("Product already exists, skipping initialization");
        }
    }
}
