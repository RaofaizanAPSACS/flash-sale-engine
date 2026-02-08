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

    /**
     * Optimistic locking version.
     * Not used by the native decrement query (which bypasses JPA),
     * but kept for any future JPA-based updates.
     */
    @Version
    private Integer version;
}
