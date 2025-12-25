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
public class ExecutionMessage {
  private String execId;
  private String orderId;
  private String clOrdId;
  private String origClOrdId;  // For cancel/replace responses
  private String symbol;
  private String side;
  private String execType;
  private String orderStatus;
  private BigDecimal lastPrice;
  private int lastQuantity;
  private int leavesQuantity;
  private int cumQuantity;
  private BigDecimal avgPrice;
  private LocalDateTime timestamp;
}