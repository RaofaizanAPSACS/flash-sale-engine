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
     * Used by JPA to detect concurrent modifications.
     * In v2, stock is managed by Redis Lua scripts so this is mainly
     * for safety during the async DB write phase.
     */
    @Version
    private Integer version;
}
