package com.example.flash_sale_engine.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inventory")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Inventory {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private String productName;
    
    @Column(nullable = false)
    private Integer stockQuantity;
    
    @Version
    private Integer version; // For Optimistic Locking
    
    public boolean hasStock() {
        return stockQuantity > 0;
    }
    
    public void decrementStock() {
        // Only decrement if stock is available
        if (stockQuantity > 0) {
            stockQuantity--;
        }
    }
}
