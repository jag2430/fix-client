package com.example.client.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "executions", indexes = {
    @Index(name = "idx_executions_cl_ord_id", columnList = "clOrdId"),
    @Index(name = "idx_executions_symbol", columnList = "symbol"),
    @Index(name = "idx_executions_exec_type", columnList = "execType"),
    @Index(name = "idx_executions_timestamp", columnList = "timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionEntity {

    @Id
    @Column(name = "exec_id", length = 50)
    private String execId;

    @Column(name = "order_id", length = 50)
    private String orderId;

    @Column(name = "cl_ord_id", nullable = false, length = 36)
    private String clOrdId;

    @Column(name = "orig_cl_ord_id", length = 36)
    private String origClOrdId;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "side", nullable = false, length = 10)
    private String side;

    @Column(name = "exec_type", nullable = false, length = 20)
    private String execType;

    @Column(name = "order_status", length = 20)
    private String orderStatus;

    @Column(name = "last_price", precision = 19, scale = 4)
    private BigDecimal lastPrice;

    @Column(name = "last_quantity")
    private int lastQuantity;

    @Column(name = "leaves_quantity")
    private int leavesQuantity;

    @Column(name = "cum_quantity")
    private int cumQuantity;

    @Column(name = "avg_price", precision = 19, scale = 4)
    private BigDecimal avgPrice;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}
