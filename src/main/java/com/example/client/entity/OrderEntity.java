package com.example.client.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_orders_symbol", columnList = "symbol"),
    @Index(name = "idx_orders_status", columnList = "status"),
    @Index(name = "idx_orders_timestamp", columnList = "timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEntity {

    @Id
    @Column(name = "cl_ord_id", length = 36)
    private String clOrdId;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "side", nullable = false, length = 10)
    private String side;

    @Column(name = "order_type", nullable = false, length = 10)
    private String orderType;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "price", precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "filled_quantity")
    private int filledQuantity;

    @Column(name = "leaves_quantity")
    private int leavesQuantity;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
