package com.example.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
  private String clOrdId;
  private String symbol;
  private String side;
  private String orderType;
  private int quantity;
  private BigDecimal price;
  private String status;
  private int filledQuantity;
  private int leavesQuantity;
  private LocalDateTime timestamp;
}
