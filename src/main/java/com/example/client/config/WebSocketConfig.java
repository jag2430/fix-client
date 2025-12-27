package com.example.client.config;

import com.example.client.websocket.PortfolioWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final PortfolioWebSocketHandler portfolioWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(portfolioWebSocketHandler, "/ws/portfolio")
                .setAllowedOrigins("*");
        
        registry.addHandler(portfolioWebSocketHandler, "/ws/executions")
                .setAllowedOrigins("*");
    }
}
