package com.example.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelRequest {
    private String originalClOrdId;
    private String symbol;
    private String side;  // BUY or SELL
}
