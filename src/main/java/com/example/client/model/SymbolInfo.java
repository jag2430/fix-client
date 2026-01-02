package com.example.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Detailed symbol information including company profile and quote data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SymbolInfo implements Serializable {

  private static final long serialVersionUID = 1L;

  // Basic info
  private String symbol;
  private String name;              // Company name
  private String exchange;          // Primary exchange
  private String currency;          // Trading currency
  private String country;           // Country of incorporation
  private String industry;          // Industry classification
  private String sector;            // Sector

  // Market data
  private BigDecimal currentPrice;
  private BigDecimal previousClose;
  private BigDecimal change;
  private BigDecimal changePercent;
  private BigDecimal high;
  private BigDecimal low;
  private BigDecimal open;

  // Company metrics
  private BigDecimal marketCap;
  private String marketCapFormatted;  // e.g., "$3.4T"
  private BigDecimal peRatio;
  private Long sharesOutstanding;

  // Validation
  private boolean valid;            // Whether symbol is valid/tradeable
  private String validationMessage; // Error message if invalid

  // Metadata
  private LocalDateTime lastUpdated;
  private String logoUrl;           // Company logo URL
  private String webUrl;            // Company website
}