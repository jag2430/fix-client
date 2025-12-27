package com.example.client.service;

import com.example.client.entity.ExecutionEntity;
import com.example.client.model.ExecutionMessage;
import com.example.client.repository.ExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final ExecutionRepository executionRepository;

    @Transactional
    public void addExecution(ExecutionMessage execution) {
        ExecutionEntity entity = toEntity(execution);
        executionRepository.save(entity);
        log.debug("Stored execution: {} for order {}", execution.getExecId(), execution.getClOrdId());
    }

    @Transactional(readOnly = true)
    public List<ExecutionMessage> getAllExecutions() {
        return executionRepository.findAllByOrderByTimestampDesc()
                .stream()
                .map(this::toMessage)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ExecutionMessage> getExecutionsByClOrdId(String clOrdId) {
        return executionRepository.findByClOrdId(clOrdId)
                .stream()
                .map(this::toMessage)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ExecutionMessage> getRecentExecutions(int limit) {
        return executionRepository.findRecentExecutions(PageRequest.of(0, limit))
                .stream()
                .map(this::toMessage)
                .collect(Collectors.toList());
    }

    @Transactional
    public void clearExecutions() {
        executionRepository.deleteAll();
        log.info("Cleared all executions");
    }

    @Transactional(readOnly = true)
    public int getExecutionCount() {
        return executionRepository.countExecutions();
    }

    private ExecutionEntity toEntity(ExecutionMessage message) {
        return ExecutionEntity.builder()
                .execId(message.getExecId())
                .orderId(message.getOrderId())
                .clOrdId(message.getClOrdId())
                .origClOrdId(message.getOrigClOrdId())
                .symbol(message.getSymbol())
                .side(message.getSide())
                .execType(message.getExecType())
                .orderStatus(message.getOrderStatus())
                .lastPrice(message.getLastPrice())
                .lastQuantity(message.getLastQuantity())
                .leavesQuantity(message.getLeavesQuantity())
                .cumQuantity(message.getCumQuantity())
                .avgPrice(message.getAvgPrice())
                .timestamp(message.getTimestamp())
                .build();
    }

    private ExecutionMessage toMessage(ExecutionEntity entity) {
        return ExecutionMessage.builder()
                .execId(entity.getExecId())
                .orderId(entity.getOrderId())
                .clOrdId(entity.getClOrdId())
                .origClOrdId(entity.getOrigClOrdId())
                .symbol(entity.getSymbol())
                .side(entity.getSide())
                .execType(entity.getExecType())
                .orderStatus(entity.getOrderStatus())
                .lastPrice(entity.getLastPrice())
                .lastQuantity(entity.getLastQuantity())
                .leavesQuantity(entity.getLeavesQuantity())
                .cumQuantity(entity.getCumQuantity())
                .avgPrice(entity.getAvgPrice())
                .timestamp(entity.getTimestamp())
                .build();
    }
}
