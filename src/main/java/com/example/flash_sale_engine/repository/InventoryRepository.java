package com.example.flash_sale_engine.repository;

import com.example.flash_sale_engine.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    Optional<Inventory> findByProductName(String productName);
    
    /**
     * Native SQL update that decrements stock atomically.
     * Only decrements if stock > 0 to prevent negative values.
     * Returns the number of rows updated (0 if stock was already 0 or negative).
     * 
     * flushAutomatically = true: Ensures pending changes are flushed before executing the query
     * clearAutomatically = true: Clears the persistence context after the query to prevent stale data
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE inventory SET stock_quantity = stock_quantity - 1 WHERE id = :id AND stock_quantity > 0", nativeQuery = true)
    int decrementStockNative(@Param("id") Long id);
}
