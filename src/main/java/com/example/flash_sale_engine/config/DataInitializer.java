package com.example.flash_sale_engine.config;

import com.example.flash_sale_engine.model.Inventory;
import com.example.flash_sale_engine.repository.InventoryRepository;
import com.example.flash_sale_engine.service.OrderQueueService;
import com.example.flash_sale_engine.service.SaleLifecycleService;
import com.example.flash_sale_engine.service.StockCacheService;
import com.example.flash_sale_engine.worker.OrderProcessorWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Initializes test data on application startup.
 * Creates a product, loads stock into Redis, and starts the sale.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final InventoryRepository inventoryRepository;
    private final StockCacheService stockCacheService;
    private final SaleLifecycleService saleLifecycleService;
    private final OrderQueueService orderQueueService;
    private final OrderProcessorWorker orderProcessorWorker;

    @Value("${flash-sale.default-stock:100}")
    private int defaultStock;

    @Override
    public void run(String... args) {
        // 1. Initialize database product
        Inventory product = inventoryRepository.findByProductName("Flash Sale Product")
                .orElse(null);

        if (product == null) {
            product = new Inventory();
            product.setProductName("Flash Sale Product");
            product.setStockQuantity(defaultStock);
            product.setVersion(0);
            product = inventoryRepository.save(product);
            log.info("Created test product: {} with stock: {} (ID: {})",
                    product.getProductName(), product.getStockQuantity(), product.getId());
        } else {
            // Reset stock for testing
            product.setStockQuantity(defaultStock);
            product = inventoryRepository.save(product);
            log.info("Reset test product: {} with stock: {} (ID: {})",
                    product.getProductName(), product.getStockQuantity(), product.getId());
        }

        // 2. Initialize Redis stock cache
        stockCacheService.initializeStock(product.getId(), defaultStock);

        // 3. Start the sale (set status to ACTIVE)
        saleLifecycleService.startSale(product.getId());

        // 4. Ensure Redis Stream consumer group exists
        orderQueueService.ensureConsumerGroup();

        // 5. Signal the worker that the stream infrastructure is ready
        orderProcessorWorker.markReady();

        log.info("Flash sale initialized: product={}, stock={}, status=ACTIVE",
                product.getId(), defaultStock);
    }
}
