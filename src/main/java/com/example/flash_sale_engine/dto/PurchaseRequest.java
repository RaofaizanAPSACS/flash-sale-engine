package com.example.flash_sale_engine.dto;

import lombok.Data;

@Data
public class PurchaseRequest {
    private String userId;
    private String queueToken;
    private Long productId;
}
