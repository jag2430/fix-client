package com.example.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRequest {
    private String symbol;
    private String side;        // BUY or SELL
    private String orderType;   // MARKET or LIMIT
    private int quantity;
    private BigDecimal price;   // Required for LIMIT orders
}
