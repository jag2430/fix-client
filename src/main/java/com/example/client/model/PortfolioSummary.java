package com.example.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioSummary implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private List<Position> positions;
    private BigDecimal totalMarketValue;
    private BigDecimal totalUnrealizedPnl;
    private BigDecimal totalRealizedPnl;
    private BigDecimal totalPnl;
    private int openPositionCount;
    private LocalDateTime lastUpdated;
}
