package com.example.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionStatus {
    private String sessionId;
    private boolean loggedOn;
    private String senderCompId;
    private String targetCompId;
}
