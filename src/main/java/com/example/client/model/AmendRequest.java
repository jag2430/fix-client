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
public class AmendRequest {
  private String originalClOrdId;
  private String symbol;
  private String side;        // BUY or SELL
  private Integer newQuantity; // Optional - null to keep existing
  private BigDecimal newPrice; // Optional - null to keep existing
}
