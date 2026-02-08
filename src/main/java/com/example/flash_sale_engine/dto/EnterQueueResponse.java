package com.example.flash_sale_engine.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnterQueueResponse {
    private String queueToken;
    private String message;
    private boolean success;
}
