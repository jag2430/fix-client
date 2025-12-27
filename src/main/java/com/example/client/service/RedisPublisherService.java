package com.example.client.service;

import com.example.client.model.ExecutionMessage;
import com.example.client.model.OrderResponse;
import com.example.client.model.Position;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service for publishing messages to Redis pub/sub channels.
 * Portfolio blotter clients can subscribe to these channels for real-time updates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisPublisherService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ChannelTopic positionsTopic;
    private final ChannelTopic executionsTopic;
    private final ChannelTopic ordersTopic;
    private final ObjectMapper redisObjectMapper;

    /**
     * Publish position update to Redis channel
     */
    public void publishPositionUpdate(Position position) {
        try {
            String message = redisObjectMapper.writeValueAsString(Map.of(
                "type", "POSITION_UPDATE",
                "data", position
            ));
            redisTemplate.convertAndSend(positionsTopic.getTopic(), message);
            log.debug("Published position update for {}: qty={}", position.getSymbol(), position.getQuantity());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize position update", e);
        }
    }

    /**
     * Publish execution update to Redis channel
     */
    public void publishExecutionUpdate(ExecutionMessage execution) {
        try {
            String message = redisObjectMapper.writeValueAsString(Map.of(
                "type", "EXECUTION",
                "data", execution
            ));
            redisTemplate.convertAndSend(executionsTopic.getTopic(), message);
            log.debug("Published execution: {} {} {} @ {}", 
                execution.getExecType(), execution.getSide(), 
                execution.getSymbol(), execution.getLastPrice());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize execution update", e);
        }
    }

    /**
     * Publish order update to Redis channel
     */
    public void publishOrderUpdate(OrderResponse order, String updateType) {
        try {
            String message = redisObjectMapper.writeValueAsString(Map.of(
                "type", updateType,  // ORDER_NEW, ORDER_FILLED, ORDER_CANCELLED, etc.
                "data", order
            ));
            redisTemplate.convertAndSend(ordersTopic.getTopic(), message);
            log.debug("Published order update: {} {}", updateType, order.getClOrdId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize order update", e);
        }
    }

    /**
     * Publish a raw message to a specific channel
     */
    public void publishRaw(String channel, String message) {
        redisTemplate.convertAndSend(channel, message);
    }
}
