package com.example.client.websocket;

import com.example.client.model.PortfolioSummary;
import com.example.client.service.PositionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for the portfolio blotter.
 * Clients connect via WebSocket and receive real-time position/execution updates
 * by subscribing to Redis pub/sub channels.
 */
@Slf4j
@Component
public class PortfolioWebSocketHandler extends TextWebSocketHandler {

    private final RedisMessageListenerContainer redisListenerContainer;
    private final ChannelTopic positionsTopic;
    private final ChannelTopic executionsTopic;
    private final ChannelTopic ordersTopic;
    private final PositionService positionService;
    private final ObjectMapper objectMapper;

    // Connected WebSocket sessions
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    // Redis message listeners per session
    private final Map<String, MessageListener> sessionListeners = new ConcurrentHashMap<>();

    public PortfolioWebSocketHandler(
            RedisMessageListenerContainer redisListenerContainer,
            ChannelTopic positionsTopic,
            ChannelTopic executionsTopic,
            ChannelTopic ordersTopic,
            PositionService positionService,
            ObjectMapper objectMapper) {
        this.redisListenerContainer = redisListenerContainer;
        this.positionsTopic = positionsTopic;
        this.executionsTopic = executionsTopic;
        this.ordersTopic = ordersTopic;
        this.positionService = positionService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        // Add global listeners that broadcast to all connected sessions
        MessageListener broadcastListener = (message, pattern) -> {
            String payload = new String(message.getBody());
            broadcastToAll(payload);
        };

        redisListenerContainer.addMessageListener(broadcastListener, positionsTopic);
        redisListenerContainer.addMessageListener(broadcastListener, executionsTopic);
        redisListenerContainer.addMessageListener(broadcastListener, ordersTopic);

        log.info("Portfolio WebSocket handler initialized, listening to Redis channels");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("WebSocket client connected: {} (total: {})", session.getId(), sessions.size());

        // Send current portfolio snapshot on connect
        sendPortfolioSnapshot(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        
        // Clean up any session-specific listeners
        MessageListener listener = sessionListeners.remove(session.getId());
        if (listener != null) {
            redisListenerContainer.removeMessageListener(listener);
        }

        log.info("WebSocket client disconnected: {} (total: {})", session.getId(), sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("Received message from {}: {}", session.getId(), payload);

        try {
            Map<String, Object> request = objectMapper.readValue(payload, Map.class);
            String action = (String) request.get("action");

            switch (action != null ? action : "") {
                case "subscribe":
                    handleSubscribe(session, request);
                    break;
                case "unsubscribe":
                    handleUnsubscribe(session, request);
                    break;
                case "getPortfolio":
                    sendPortfolioSnapshot(session);
                    break;
                case "ping":
                    sendMessage(session, Map.of("type", "pong", "timestamp", System.currentTimeMillis()));
                    break;
                default:
                    sendMessage(session, Map.of("type", "error", "message", "Unknown action: " + action));
            }
        } catch (Exception e) {
            log.error("Error handling WebSocket message", e);
            sendMessage(session, Map.of("type", "error", "message", e.getMessage()));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}: {}", session.getId(), exception.getMessage());
        sessions.remove(session);
    }

    /**
     * Handle subscription requests for specific symbols or channels
     */
    private void handleSubscribe(WebSocketSession session, Map<String, Object> request) throws IOException {
        String channel = (String) request.get("channel");
        log.info("Session {} subscribing to channel: {}", session.getId(), channel);
        
        sendMessage(session, Map.of(
            "type", "subscribed",
            "channel", channel,
            "message", "Successfully subscribed to " + channel
        ));
    }

    /**
     * Handle unsubscription requests
     */
    private void handleUnsubscribe(WebSocketSession session, Map<String, Object> request) throws IOException {
        String channel = (String) request.get("channel");
        log.info("Session {} unsubscribing from channel: {}", session.getId(), channel);
        
        sendMessage(session, Map.of(
            "type", "unsubscribed",
            "channel", channel
        ));
    }

    /**
     * Send current portfolio snapshot to a session
     */
    private void sendPortfolioSnapshot(WebSocketSession session) throws IOException {
        PortfolioSummary summary = positionService.getPortfolioSummary();
        sendMessage(session, Map.of(
            "type", "PORTFOLIO_SNAPSHOT",
            "data", summary
        ));
    }

    /**
     * Broadcast message to all connected sessions
     */
    private void broadcastToAll(String message) {
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.error("Failed to send message to session {}", session.getId(), e);
                }
            }
        }
    }

    /**
     * Send a message to a specific session
     */
    private void sendMessage(WebSocketSession session, Object payload) throws IOException {
        if (session.isOpen()) {
            String json = objectMapper.writeValueAsString(payload);
            session.sendMessage(new TextMessage(json));
        }
    }

    /**
     * Get count of connected clients
     */
    public int getConnectedClientCount() {
        return sessions.size();
    }
}
