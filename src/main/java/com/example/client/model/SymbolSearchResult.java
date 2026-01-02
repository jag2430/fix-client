package com.example.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Represents a symbol search result from Finnhub.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SymbolSearchResult implements Serializable {

  private static final long serialVersionUID = 1L;

  private String symbol;          // Ticker symbol (e.g., "AAPL")
  private String description;     // Company name (e.g., "Apple Inc")
  private String type;            // Security type (e.g., "Common Stock")
  private String displaySymbol;   // Display symbol
  private String currency;        // Trading currency (e.g., "USD")
  private String exchange;        // Primary exchange (e.g., "NASDAQ")
  private String figi;            // FIGI identifier
  private String mic;             // Market Identifier Code
}