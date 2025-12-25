package com.example.client.service;

import com.example.client.model.ExecutionMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class ExecutionService {
    
    // All executions in order received
    private final List<ExecutionMessage> executions = new CopyOnWriteArrayList<>();
    
    // Executions grouped by clOrdId
    private final Map<String, List<ExecutionMessage>> executionsByOrder = new ConcurrentHashMap<>();
    
    public void addExecution(ExecutionMessage execution) {
        executions.add(execution);
        
        executionsByOrder
            .computeIfAbsent(execution.getClOrdId(), k -> new CopyOnWriteArrayList<>())
            .add(execution);
        
        log.debug("Stored execution: {} for order {}", execution.getExecId(), execution.getClOrdId());
    }
    
    public List<ExecutionMessage> getAllExecutions() {
        return new ArrayList<>(executions);
    }
    
    public List<ExecutionMessage> getExecutionsByClOrdId(String clOrdId) {
        return executionsByOrder.getOrDefault(clOrdId, Collections.emptyList());
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
        log.info("Cleared all executions");
    }
    
    public int getExecutionCount() {
        return executions.size();
    }
}
