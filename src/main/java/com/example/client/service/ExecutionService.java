package com.example.client.service;

import com.example.client.model.ExecutionMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class ExecutionService {

    private static final String EXECUTION_LIST_KEY = "executions:list";
    private static final String EXECUTION_BY_ORDER_KEY_PREFIX = "executions:order:";

    private final RedisTemplate<String, ExecutionMessage> executionRedisTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisPublisherService publisherService;
    private final PositionService positionService;

    @Value("${redis.cache.orders-ttl:86400}")
    private long executionsTtl;

    // Local cache for fast access
    private final List<ExecutionMessage> executions = new CopyOnWriteArrayList<>();
    private final Map<String, List<ExecutionMessage>> executionsByOrder = new ConcurrentHashMap<>();

    public ExecutionService(RedisTemplate<String, ExecutionMessage> executionRedisTemplate,
                           RedisTemplate<String, Object> redisTemplate,
                           RedisPublisherService publisherService,
                           PositionService positionService) {
        this.executionRedisTemplate = executionRedisTemplate;
        this.redisTemplate = redisTemplate;
        this.publisherService = publisherService;
        this.positionService = positionService;
    }

    public void addExecution(ExecutionMessage execution) {
        // Add to local cache
        executions.add(execution);
        executionsByOrder
            .computeIfAbsent(execution.getClOrdId(), k -> new CopyOnWriteArrayList<>())
            .add(execution);

        // Cache in Redis
        try {
            // Push to Redis list
            redisTemplate.opsForList().rightPush(EXECUTION_LIST_KEY, execution);
            redisTemplate.expire(EXECUTION_LIST_KEY, Duration.ofSeconds(executionsTtl));

            // Also index by order ID
            String orderKey = EXECUTION_BY_ORDER_KEY_PREFIX + execution.getClOrdId();
            redisTemplate.opsForList().rightPush(orderKey, execution);
            redisTemplate.expire(orderKey, Duration.ofSeconds(executionsTtl));
        } catch (Exception e) {
            log.warn("Failed to cache execution in Redis: {}", e.getMessage());
        }

        // Publish to Redis pub/sub for real-time updates
        publisherService.publishExecutionUpdate(execution);

        // Update positions for fills
        if ("FILL".equals(execution.getExecType()) || "PARTIAL_FILL".equals(execution.getExecType())) {
            positionService.updatePositionFromExecution(execution);
        }

        log.debug("Stored execution: {} for order {}", execution.getExecId(), execution.getClOrdId());
    }

    public List<ExecutionMessage> getAllExecutions() {
        return new ArrayList<>(executions);
    }

    public List<ExecutionMessage> getExecutionsByClOrdId(String clOrdId) {
        // Check local cache first
        List<ExecutionMessage> cached = executionsByOrder.get(clOrdId);
        if (cached != null && !cached.isEmpty()) {
            return new ArrayList<>(cached);
        }

        // Try Redis
        try {
            String orderKey = EXECUTION_BY_ORDER_KEY_PREFIX + clOrdId;
            List<Object> redisExecs = redisTemplate.opsForList().range(orderKey, 0, -1);
            if (redisExecs != null && !redisExecs.isEmpty()) {
                List<ExecutionMessage> result = new ArrayList<>();
                for (Object obj : redisExecs) {
                    if (obj instanceof ExecutionMessage) {
                        result.add((ExecutionMessage) obj);
                    }
                }
                // Update local cache
                executionsByOrder.put(clOrdId, new CopyOnWriteArrayList<>(result));
                return result;
            }
        } catch (Exception e) {
            log.warn("Failed to get executions from Redis for order {}: {}", clOrdId, e.getMessage());
        }

        return Collections.emptyList();
    }

    public List<ExecutionMessage> getRecentExecutions(int limit) {
        int size = executions.size();
        if (size <= limit) {
            return new ArrayList<>(executions);
        }
        return new ArrayList<>(executions.subList(size - limit, size));
    }

    public void clearExecutions() {
        executions.clear();
        executionsByOrder.clear();

        // Clear from Redis
        try {
            redisTemplate.delete(EXECUTION_LIST_KEY);
            // Note: This doesn't clear individual order keys, would need to track them
        } catch (Exception e) {
            log.warn("Failed to clear executions from Redis: {}", e.getMessage());
        }

        log.info("Cleared all executions");
    }

    public int getExecutionCount() {
        return executions.size();
    }
}
