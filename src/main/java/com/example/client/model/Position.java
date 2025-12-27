package com.example.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Position implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String symbol;
    private int quantity;           // Net position (positive = long, negative = short)
    private BigDecimal avgCost;     // Average cost basis
    private BigDecimal currentPrice; // Current market price
    private BigDecimal marketValue;  // Current market value
    private BigDecimal unrealizedPnl; // Unrealized P&L
    private BigDecimal realizedPnl;   // Realized P&L
    private BigDecimal totalCost;     // Total cost basis
    private LocalDateTime lastUpdated;
    
    /**
     * Update position with a fill
     */
    public void applyFill(String side, int fillQty, BigDecimal fillPrice) {
        if (fillQty <= 0) return;
        
        boolean isBuy = "BUY".equalsIgnoreCase(side);
        int fillDirection = isBuy ? 1 : -1;
        int newQuantity = this.quantity + (fillQty * fillDirection);
        
        // Calculate realized P&L when closing or reversing position
        if ((isBuy && this.quantity < 0) || (!isBuy && this.quantity > 0)) {
            // Closing or reversing - calculate realized P&L
            int closingQty = Math.min(Math.abs(this.quantity), fillQty);
            BigDecimal pnlPerShare = isBuy 
                ? this.avgCost.subtract(fillPrice)  // Covering short
                : fillPrice.subtract(this.avgCost); // Selling long
            
            BigDecimal realizedFromThisFill = pnlPerShare.multiply(BigDecimal.valueOf(closingQty));
            this.realizedPnl = (this.realizedPnl != null ? this.realizedPnl : BigDecimal.ZERO)
                .add(realizedFromThisFill);
        }
        
        // Update average cost for new/added position
        if ((isBuy && newQuantity > 0 && this.quantity >= 0) || 
            (!isBuy && newQuantity < 0 && this.quantity <= 0)) {
            // Adding to existing position - weighted average
            BigDecimal existingCost = this.avgCost.multiply(BigDecimal.valueOf(Math.abs(this.quantity)));
            BigDecimal newCost = fillPrice.multiply(BigDecimal.valueOf(fillQty));
            BigDecimal totalShares = BigDecimal.valueOf(Math.abs(newQuantity));
            
            if (totalShares.compareTo(BigDecimal.ZERO) > 0) {
                this.avgCost = existingCost.add(newCost).divide(totalShares, 4, RoundingMode.HALF_UP);
            }
        } else if ((isBuy && this.quantity < 0 && newQuantity > 0) ||
                   (!isBuy && this.quantity > 0 && newQuantity < 0)) {
            // Position reversed - new avg cost is the fill price
            this.avgCost = fillPrice;
        }
        
        this.quantity = newQuantity;
        this.totalCost = this.avgCost.multiply(BigDecimal.valueOf(Math.abs(this.quantity)));
        this.lastUpdated = LocalDateTime.now();
        
        updateMarketValue();
    }
    
    /**
     * Update market value and unrealized P&L with current price
     */
    public void updateMarketPrice(BigDecimal price) {
        this.currentPrice = price;
        updateMarketValue();
        this.lastUpdated = LocalDateTime.now();
    }
    
    private void updateMarketValue() {
        if (this.currentPrice != null && this.quantity != 0) {
            this.marketValue = this.currentPrice.multiply(BigDecimal.valueOf(Math.abs(this.quantity)));
            
            BigDecimal costBasis = this.avgCost.multiply(BigDecimal.valueOf(Math.abs(this.quantity)));
            if (this.quantity > 0) {
                // Long position: profit when market value > cost
                this.unrealizedPnl = this.marketValue.subtract(costBasis);
            } else {
                // Short position: profit when market value < cost
                this.unrealizedPnl = costBasis.subtract(this.marketValue);
            }
        } else {
            this.marketValue = BigDecimal.ZERO;
            this.unrealizedPnl = BigDecimal.ZERO;
        }
    }
    
    /**
     * Create a new position from an initial fill
     */
    public static Position fromFill(String symbol, String side, int quantity, BigDecimal price) {
        Position position = Position.builder()
            .symbol(symbol)
            .quantity(0)
            .avgCost(BigDecimal.ZERO)
            .currentPrice(price)
            .marketValue(BigDecimal.ZERO)
            .unrealizedPnl(BigDecimal.ZERO)
            .realizedPnl(BigDecimal.ZERO)
            .totalCost(BigDecimal.ZERO)
            .lastUpdated(LocalDateTime.now())
            .build();
        
        position.applyFill(side, quantity, price);
        return position;
    }
    
    /**
     * Check if position is flat (no shares held)
     */
    public boolean isFlat() {
        return this.quantity == 0;
    }
    
    /**
     * Check if position is long
     */
    public boolean isLong() {
        return this.quantity > 0;
    }
    
    /**
     * Check if position is short
     */
    public boolean isShort() {
        return this.quantity < 0;
    }
}
