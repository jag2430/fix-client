package com.example.client.service;

import com.example.client.model.ExecutionMessage;
import com.example.client.model.Position;
import com.example.client.model.PortfolioSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for tracking and managing portfolio positions.
 * Uses Redis for caching and persistence, with local in-memory cache for fast access.
 */
@Slf4j
@Service
public class PositionService {

    private static final String POSITION_KEY_PREFIX = "position:";
    private static final String POSITIONS_SET_KEY = "positions:symbols";

    private final RedisTemplate<String, Position> positionRedisTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisPublisherService publisherService;

    @Value("${redis.cache.positions-ttl:3600}")
    private long positionsTtl;

    // Local cache for fast access
    private final Map<String, Position> localPositionCache = new ConcurrentHashMap<>();

    public PositionService(RedisTemplate<String, Position> positionRedisTemplate,
                          RedisTemplate<String, Object> redisTemplate,
                          RedisPublisherService publisherService) {
        this.positionRedisTemplate = positionRedisTemplate;
        this.redisTemplate = redisTemplate;
        this.publisherService = publisherService;
    }

    /**
     * Update position based on execution report (fill)
     */
    public Position updatePositionFromExecution(ExecutionMessage execution) {
        // Only process fills
        if (!"FILL".equals(execution.getExecType()) && 
            !"PARTIAL_FILL".equals(execution.getExecType())) {
            return getPosition(execution.getSymbol());
        }

        String symbol = execution.getSymbol();
        int fillQty = execution.getLastQuantity();
        BigDecimal fillPrice = execution.getLastPrice();
        String side = execution.getSide();

        // Get or create position
        Position position = getPosition(symbol);
        if (position == null) {
            position = Position.fromFill(symbol, side, fillQty, fillPrice);
        } else {
            position.applyFill(side, fillQty, fillPrice);
        }

        // Save to cache and Redis
        savePosition(position);

        // Publish update
        publisherService.publishPositionUpdate(position);

        log.info("Position updated: {} qty={} avgCost={} unrealizedPnL={}",
            symbol, position.getQuantity(), position.getAvgCost(), position.getUnrealizedPnl());

        return position;
    }

    /**
     * Update position with current market price
     */
    public Position updateMarketPrice(String symbol, BigDecimal price) {
        Position position = getPosition(symbol);
        if (position != null && !position.isFlat()) {
            position.updateMarketPrice(price);
            savePosition(position);
            publisherService.publishPositionUpdate(position);
        }
        return position;
    }

    /**
     * Get position for a symbol
     */
    public Position getPosition(String symbol) {
        // Check local cache first
        Position position = localPositionCache.get(symbol);
        if (position != null) {
            return position;
        }

        // Try Redis
        try {
            position = positionRedisTemplate.opsForValue().get(POSITION_KEY_PREFIX + symbol);
            if (position != null) {
                localPositionCache.put(symbol, position);
            }
        } catch (Exception e) {
            log.warn("Failed to get position from Redis for {}: {}", symbol, e.getMessage());
        }

        return position;
    }

    /**
     * Get all positions
     */
    public List<Position> getAllPositions() {
        // First sync from Redis
        syncFromRedis();
        return new ArrayList<>(localPositionCache.values());
    }

    /**
     * Get only open (non-flat) positions
     */
    public List<Position> getOpenPositions() {
        return getAllPositions().stream()
            .filter(p -> !p.isFlat())
            .collect(Collectors.toList());
    }

    /**
     * Get portfolio summary
     */
    public PortfolioSummary getPortfolioSummary() {
        List<Position> positions = getOpenPositions();

        BigDecimal totalMarketValue = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        BigDecimal totalRealizedPnl = BigDecimal.ZERO;

        for (Position p : positions) {
            if (p.getMarketValue() != null) {
                totalMarketValue = totalMarketValue.add(p.getMarketValue());
            }
            if (p.getUnrealizedPnl() != null) {
                totalUnrealizedPnl = totalUnrealizedPnl.add(p.getUnrealizedPnl());
            }
            if (p.getRealizedPnl() != null) {
                totalRealizedPnl = totalRealizedPnl.add(p.getRealizedPnl());
            }
        }

        return PortfolioSummary.builder()
            .positions(positions)
            .totalMarketValue(totalMarketValue)
            .totalUnrealizedPnl(totalUnrealizedPnl)
            .totalRealizedPnl(totalRealizedPnl)
            .totalPnl(totalUnrealizedPnl.add(totalRealizedPnl))
            .openPositionCount(positions.size())
            .lastUpdated(LocalDateTime.now())
            .build();
    }

    /**
     * Save position to cache and Redis
     */
    private void savePosition(Position position) {
        String symbol = position.getSymbol();

        // Update local cache
        localPositionCache.put(symbol, position);

        // Save to Redis
        try {
            positionRedisTemplate.opsForValue().set(
                POSITION_KEY_PREFIX + symbol,
                position,
                Duration.ofSeconds(positionsTtl)
            );

            // Track symbol in positions set
            redisTemplate.opsForSet().add(POSITIONS_SET_KEY, symbol);
        } catch (Exception e) {
            log.warn("Failed to save position to Redis for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Sync local cache from Redis
     */
    private void syncFromRedis() {
        try {
            Set<Object> symbols = redisTemplate.opsForSet().members(POSITIONS_SET_KEY);
            if (symbols != null) {
                for (Object symbolObj : symbols) {
                    String symbol = symbolObj.toString();
                    if (!localPositionCache.containsKey(symbol)) {
                        Position position = positionRedisTemplate.opsForValue()
                            .get(POSITION_KEY_PREFIX + symbol);
                        if (position != null) {
                            localPositionCache.put(symbol, position);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to sync positions from Redis: {}", e.getMessage());
        }
    }

    /**
     * Clear all positions (for testing/reset)
     */
    public void clearAllPositions() {
        localPositionCache.clear();
        try {
            Set<Object> symbols = redisTemplate.opsForSet().members(POSITIONS_SET_KEY);
            if (symbols != null) {
                for (Object symbol : symbols) {
                    redisTemplate.delete(POSITION_KEY_PREFIX + symbol);
                }
                redisTemplate.delete(POSITIONS_SET_KEY);
            }
        } catch (Exception e) {
            log.warn("Failed to clear positions from Redis: {}", e.getMessage());
        }
        log.info("Cleared all positions");
    }

    /**
     * Get position count
     */
    public int getPositionCount() {
        return (int) getAllPositions().stream()
            .filter(p -> !p.isFlat())
            .count();
    }
}
