package com.example.client.service;

import com.example.client.model.AmendRequest;
import com.example.client.model.CancelRequest;
import com.example.client.model.OrderRequest;
import com.example.client.model.OrderResponse;
import com.example.client.model.SessionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderService {

    private static final String ORDER_KEY_PREFIX = "order:";
    private static final String ORDERS_SET_KEY = "orders:all";
    private static final String OPEN_ORDERS_SET_KEY = "orders:open";

    private final SocketInitiator initiator;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisPublisherService publisherService;

    @Value("${redis.cache.orders-ttl:86400}")
    private long ordersTtl;

    // Local cache for sent orders
    private final Map<String, OrderResponse> sentOrders = new ConcurrentHashMap<>();

    public OrderService(@Lazy SocketInitiator initiator,
                       RedisTemplate<String, Object> redisTemplate,
                       RedisPublisherService publisherService) {
        this.initiator = initiator;
        this.redisTemplate = redisTemplate;
        this.publisherService = publisherService;
    }

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

        OrderResponse response = OrderResponse.builder()
            .clOrdId(clOrdId)
            .symbol(request.getSymbol())
            .side(request.getSide().toUpperCase())
            .orderType(request.getOrderType().toUpperCase())
            .quantity(request.getQuantity())
            .price(request.getPrice())
            .status("PENDING")
            .leavesQuantity(request.getQuantity())
            .filledQuantity(0)
            .timestamp(LocalDateTime.now())
            .build();

        // Store locally and in Redis
        saveOrder(response);

        // Publish order event
        publisherService.publishOrderUpdate(response, "ORDER_NEW");

        log.info("Sent order: {} {} {} {} @ {}",
            clOrdId, request.getSide(), request.getQuantity(),
            request.getSymbol(), request.getPrice());

        return response;
    }

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
        OrderResponse original = sentOrders.get(request.getOriginalClOrdId());
        if (original != null) {
            original.setStatus("PENDING_CANCEL");
            saveOrder(original);
            publisherService.publishOrderUpdate(original, "ORDER_PENDING_CANCEL");
        }

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

    public OrderResponse amendOrder(AmendRequest request) throws SessionNotFound {
        SessionID sessionId = getActiveSession();

        if (sessionId == null) {
            throw new IllegalStateException("No active FIX session available");
        }

        OrderResponse originalOrder = sentOrders.get(request.getOriginalClOrdId());
        if (originalOrder == null) {
            throw new IllegalArgumentException("Original order not found: " + request.getOriginalClOrdId());
        }

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

        OrderResponse response = OrderResponse.builder()
            .clOrdId(clOrdId)
            .symbol(request.getSymbol())
            .side(request.getSide().toUpperCase())
            .orderType(originalOrder.getOrderType())
            .quantity(effectiveQty)
            .price(effectivePrice)
            .status("PENDING_REPLACE")
            .timestamp(LocalDateTime.now())
            .build();

        saveOrder(response);
        publisherService.publishOrderUpdate(response, "ORDER_PENDING_REPLACE");

        log.info("Sent amend request: {} for original order {} newQty={} newPrice={}",
            clOrdId, request.getOriginalClOrdId(), effectiveQty, effectivePrice);

        return response;
    }

    /**
     * Update order status based on execution report.
     */
    public void updateOrderStatus(String clOrdId, String status, int filledQty, int leavesQty) {
        OrderResponse order = sentOrders.get(clOrdId);
        if (order != null) {
            order.setStatus(status);
            order.setFilledQuantity(filledQty);
            order.setLeavesQuantity(leavesQty);
            saveOrder(order);
            publisherService.publishOrderUpdate(order, "ORDER_" + status);
            log.debug("Updated order {} status to {}", clOrdId, status);
        }
    }

    /**
     * Handle order replacement
     */
    public void handleOrderReplaced(String origClOrdId, String newClOrdId, String status,
                                   int filledQty, int leavesQty) {
        OrderResponse oldOrder = sentOrders.get(origClOrdId);
        if (oldOrder != null) {
            oldOrder.setStatus("REPLACED");
            saveOrder(oldOrder);
            publisherService.publishOrderUpdate(oldOrder, "ORDER_REPLACED");
        }

        updateOrderStatus(newClOrdId, status, filledQty, leavesQty);
        log.debug("Order replaced: {} -> {}", origClOrdId, newClOrdId);
    }

    public void updateOrderStatusForCancel(String origClOrdId, String status, int filledQty, int leavesQty) {
        OrderResponse order = sentOrders.get(origClOrdId);
        if (order != null) {
            order.setStatus(status);
            order.setFilledQuantity(filledQty);
            order.setLeavesQuantity(leavesQty);
            saveOrder(order);
            publisherService.publishOrderUpdate(order, "ORDER_" + status);
            log.debug("Updated ORIGINAL order {} status to {}", origClOrdId, status);
        } else {
            log.warn("Cancel update: original order {} not found in sentOrders", origClOrdId);
        }
    }

    public void removeOrder(String clOrdId) {
        sentOrders.remove(clOrdId);
        try {
            redisTemplate.delete(ORDER_KEY_PREFIX + clOrdId);
            redisTemplate.opsForSet().remove(ORDERS_SET_KEY, clOrdId);
            redisTemplate.opsForSet().remove(OPEN_ORDERS_SET_KEY, clOrdId);
        } catch (Exception e) {
            log.warn("Failed to remove order from Redis: {}", e.getMessage());
        }
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

    public List<OrderResponse> getSentOrders() {
        return new ArrayList<>(sentOrders.values());
    }

    public List<OrderResponse> getOpenOrders() {
        return sentOrders.values().stream()
            .filter(order -> {
                String status = order.getStatus();
                return status != null &&
                    !status.equals("FILLED") &&
                    !status.equals("CANCELLED") &&
                    !status.equals("REJECTED") &&
                    !status.equals("REPLACED");
            })
            .collect(Collectors.toList());
    }

    public OrderResponse getOrderByClOrdId(String clOrdId) {
        // Check local cache first
        OrderResponse order = sentOrders.get(clOrdId);
        if (order != null) {
            return order;
        }

        // Try Redis
        try {
            Object cached = redisTemplate.opsForValue().get(ORDER_KEY_PREFIX + clOrdId);
            if (cached instanceof OrderResponse) {
                order = (OrderResponse) cached;
                sentOrders.put(clOrdId, order);
                return order;
            }
        } catch (Exception e) {
            log.warn("Failed to get order from Redis: {}", e.getMessage());
        }

        return null;
    }

    private void saveOrder(OrderResponse order) {
        String clOrdId = order.getClOrdId();
        sentOrders.put(clOrdId, order);

        try {
            // Save to Redis
            redisTemplate.opsForValue().set(
                ORDER_KEY_PREFIX + clOrdId,
                order,
                Duration.ofSeconds(ordersTtl)
            );

            // Track in sets
            redisTemplate.opsForSet().add(ORDERS_SET_KEY, clOrdId);

            // Update open orders set
            String status = order.getStatus();
            if (status != null && !status.equals("FILLED") && !status.equals("CANCELLED") &&
                !status.equals("REJECTED") && !status.equals("REPLACED")) {
                redisTemplate.opsForSet().add(OPEN_ORDERS_SET_KEY, clOrdId);
            } else {
                redisTemplate.opsForSet().remove(OPEN_ORDERS_SET_KEY, clOrdId);
            }
        } catch (Exception e) {
            log.warn("Failed to save order to Redis: {}", e.getMessage());
        }
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
}
