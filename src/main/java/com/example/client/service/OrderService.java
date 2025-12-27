package com.example.client.service;

import com.example.client.entity.OrderEntity;
import com.example.client.model.AmendRequest;
import com.example.client.model.CancelRequest;
import com.example.client.model.OrderRequest;
import com.example.client.model.OrderResponse;
import com.example.client.model.SessionStatus;
import com.example.client.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderService {

    private final SocketInitiator initiator;
    private final OrderRepository orderRepository;

    public OrderService(@Lazy SocketInitiator initiator, OrderRepository orderRepository) {
        this.initiator = initiator;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse sendOrder(OrderRequest request) throws SessionNotFound {
        SessionID sessionId = getActiveSession();

        if (sessionId == null) {
            throw new IllegalStateException("No active FIX session available");
        }

        String clOrdId = generateClOrdId();

        NewOrderSingle order = new NewOrderSingle(
                new ClOrdID(clOrdId),
                new Side(request.getSide().equalsIgnoreCase("BUY") ? Side.BUY : Side.SELL),
                new TransactTime(LocalDateTime.now()),
                new OrdType(request.getOrderType().equalsIgnoreCase("MARKET")
                        ? OrdType.MARKET : OrdType.LIMIT)
        );

        order.set(new Symbol(request.getSymbol()));
        order.set(new OrderQty(request.getQuantity()));
        order.set(new TimeInForce(TimeInForce.DAY));

        if (request.getOrderType().equalsIgnoreCase("LIMIT")) {
            if (request.getPrice() == null) {
                throw new IllegalArgumentException("Price is required for LIMIT orders");
            }
            order.set(new Price(request.getPrice().doubleValue()));
        }

        Session.sendToTarget(order, sessionId);

        // Save to database
        OrderEntity entity = OrderEntity.builder()
                .clOrdId(clOrdId)
                .symbol(request.getSymbol())
                .side(request.getSide().toUpperCase())
                .orderType(request.getOrderType().toUpperCase())
                .quantity(request.getQuantity())
                .price(request.getPrice())
                .status("PENDING")
                .filledQuantity(0)
                .leavesQuantity(request.getQuantity())
                .timestamp(LocalDateTime.now())
                .build();

        orderRepository.save(entity);

        log.info("Sent order: {} {} {} {} @ {}",
                clOrdId, request.getSide(), request.getQuantity(),
                request.getSymbol(), request.getPrice());

        return toResponse(entity);
    }

    @Transactional
    public OrderResponse cancelOrder(CancelRequest request) throws SessionNotFound {
        SessionID sessionId = getActiveSession();

        if (sessionId == null) {
            throw new IllegalStateException("No active FIX session available");
        }

        String clOrdId = generateClOrdId();

        OrderCancelRequest cancelRequest = new OrderCancelRequest(
                new OrigClOrdID(request.getOriginalClOrdId()),
                new ClOrdID(clOrdId),
                new Side(request.getSide().equalsIgnoreCase("BUY") ? Side.BUY : Side.SELL),
                new TransactTime(LocalDateTime.now())
        );

        cancelRequest.set(new Symbol(request.getSymbol()));
        cancelRequest.set(new OrderQty(0));

        Session.sendToTarget(cancelRequest, sessionId);

        // Mark the ORIGINAL order as pending cancel
        orderRepository.findById(request.getOriginalClOrdId()).ifPresent(original -> {
            original.setStatus("PENDING_CANCEL");
            orderRepository.save(original);
        });

        OrderResponse response = OrderResponse.builder()
                .clOrdId(clOrdId)
                .symbol(request.getSymbol())
                .side(request.getSide().toUpperCase())
                .status("PENDING_CANCEL")
                .timestamp(LocalDateTime.now())
                .build();

        log.info("Sent cancel request: {} for original order {}", clOrdId, request.getOriginalClOrdId());

        return response;
    }

    @Transactional
    public OrderResponse amendOrder(AmendRequest request) throws SessionNotFound {
        SessionID sessionId = getActiveSession();

        if (sessionId == null) {
            throw new IllegalStateException("No active FIX session available");
        }

        // Get the original order from database
        OrderEntity originalOrder = orderRepository.findById(request.getOriginalClOrdId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Original order not found: " + request.getOriginalClOrdId()));

        String clOrdId = generateClOrdId();

        OrderCancelReplaceRequest amendRequest = new OrderCancelReplaceRequest(
                new OrigClOrdID(request.getOriginalClOrdId()),
                new ClOrdID(clOrdId),
                new Side(request.getSide().equalsIgnoreCase("BUY") ? Side.BUY : Side.SELL),
                new TransactTime(LocalDateTime.now()),
                new OrdType(originalOrder.getOrderType().equalsIgnoreCase("MARKET")
                        ? OrdType.MARKET : OrdType.LIMIT)
        );

        amendRequest.set(new Symbol(request.getSymbol()));

        int effectiveQty = request.getNewQuantity() != null
                ? request.getNewQuantity()
                : originalOrder.getQuantity();
        amendRequest.set(new OrderQty(effectiveQty));

        BigDecimal effectivePrice = request.getNewPrice() != null
                ? request.getNewPrice()
                : originalOrder.getPrice();
        if (effectivePrice != null) {
            amendRequest.set(new Price(effectivePrice.doubleValue()));
        }

        Session.sendToTarget(amendRequest, sessionId);

        // Save new order to database
        OrderEntity newEntity = OrderEntity.builder()
                .clOrdId(clOrdId)
                .symbol(request.getSymbol())
                .side(request.getSide().toUpperCase())
                .orderType(originalOrder.getOrderType())
                .quantity(effectiveQty)
                .price(effectivePrice)
                .status("PENDING_REPLACE")
                .filledQuantity(0)
                .leavesQuantity(effectiveQty)
                .timestamp(LocalDateTime.now())
                .build();

        orderRepository.save(newEntity);

        log.info("Sent amend request: {} for original order {} newQty={} newPrice={}",
                clOrdId, request.getOriginalClOrdId(), effectiveQty, effectivePrice);

        return toResponse(newEntity);
    }

    @Transactional
    public void updateOrderStatus(String clOrdId, String status, int filledQty, int leavesQty) {
        orderRepository.findById(clOrdId).ifPresent(order -> {
            order.setStatus(status);
            order.setFilledQuantity(filledQty);
            order.setLeavesQuantity(leavesQty);
            orderRepository.save(order);
            log.debug("Updated order {} status to {}", clOrdId, status);
        });
    }

    @Transactional
    public void handleOrderReplaced(String origClOrdId, String newClOrdId, String status,
                                    int filledQty, int leavesQty) {
        // Mark old order as replaced
        orderRepository.findById(origClOrdId).ifPresent(oldOrder -> {
            oldOrder.setStatus("REPLACED");
            orderRepository.save(oldOrder);
        });

        // Update new order
        updateOrderStatus(newClOrdId, status, filledQty, leavesQty);

        log.debug("Order replaced: {} -> {}", origClOrdId, newClOrdId);
    }

    @Transactional
    public void updateOrderStatusForCancel(String origClOrdId, String status, int filledQty, int leavesQty) {
        orderRepository.findById(origClOrdId).ifPresent(order -> {
            order.setStatus(status);
            order.setFilledQuantity(filledQty);
            order.setLeavesQuantity(leavesQty);
            orderRepository.save(order);
            log.debug("Updated ORIGINAL order {} status to {}", origClOrdId, status);
        });
    }

    @Transactional
    public void removeOrder(String clOrdId) {
        orderRepository.deleteById(clOrdId);
        log.debug("Removed order {} from tracking", clOrdId);
    }

    public List<SessionStatus> getSessionStatus() {
        List<SessionStatus> statuses = new ArrayList<>();

        for (SessionID sessionId : initiator.getSessions()) {
            Session session = Session.lookupSession(sessionId);
            statuses.add(SessionStatus.builder()
                    .sessionId(sessionId.toString())
                    .loggedOn(session != null && session.isLoggedOn())
                    .senderCompId(sessionId.getSenderCompID())
                    .targetCompId(sessionId.getTargetCompID())
                    .build());
        }

        return statuses;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getSentOrders() {
        return orderRepository.findAllByOrderByTimestampDesc()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOpenOrders() {
        return orderRepository.findOpenOrdersOrderByTimestampDesc()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderByClOrdId(String clOrdId) {
        return orderRepository.findById(clOrdId)
                .map(this::toResponse)
                .orElse(null);
    }

    private SessionID getActiveSession() {
        for (SessionID sessionId : initiator.getSessions()) {
            Session session = Session.lookupSession(sessionId);
            if (session != null && session.isLoggedOn()) {
                return sessionId;
            }
        }
        return null;
    }

    private String generateClOrdId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private OrderResponse toResponse(OrderEntity entity) {
        return OrderResponse.builder()
                .clOrdId(entity.getClOrdId())
                .symbol(entity.getSymbol())
                .side(entity.getSide())
                .orderType(entity.getOrderType())
                .quantity(entity.getQuantity())
                .price(entity.getPrice())
                .status(entity.getStatus())
                .filledQuantity(entity.getFilledQuantity())
                .leavesQuantity(entity.getLeavesQuantity())
                .timestamp(entity.getTimestamp())
                .build();
    }
}
