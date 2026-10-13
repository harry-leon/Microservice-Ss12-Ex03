package com.example.flashsale.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseResponse {
    private boolean success;
    private String message;
    private Long productId;
    private Integer remainingStock;
    private String customerId;
}
