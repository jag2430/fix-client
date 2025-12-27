package com.example.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Market data update model for price updates from market data providers.
 * Designed to work with Alpaca, Polygon, or other market data APIs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDataUpdate implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String symbol;
    private BigDecimal price;
    private BigDecimal bidPrice;
    private BigDecimal askPrice;
    private BigDecimal bidSize;
    private BigDecimal askSize;
    private BigDecimal volume;
    private BigDecimal vwap;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal previousClose;
    private BigDecimal change;
    private BigDecimal changePercent;
    private String source;          // alpaca, polygon, etc.
    private String updateType;      // TRADE, QUOTE, BAR
    private LocalDateTime timestamp;
}
