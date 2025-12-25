package com.example.client.service;

import com.example.client.model.AmendRequest;
import com.example.client.model.CancelRequest;
import com.example.client.model.OrderRequest;
import com.example.client.model.OrderResponse;
import com.example.client.model.SessionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;

import java.math.BigDecimal;
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

  private final SocketInitiator initiator;

  // Track sent orders
  private final Map<String, OrderResponse> sentOrders = new ConcurrentHashMap<>();

  // Use @Lazy to break circular dependency
  public OrderService(@Lazy SocketInitiator initiator) {
    this.initiator = initiator;
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
        .timestamp(LocalDateTime.now())
        .build();

    sentOrders.put(clOrdId, response);

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
    cancelRequest.set(new OrderQty(0)); // Required field but not used for cancel

    Session.sendToTarget(cancelRequest, sessionId);

    // Mark the ORIGINAL order as pending cancel immediately (UI feedback)
    OrderResponse original = sentOrders.get(request.getOriginalClOrdId());
    if (original != null) {
      original.setStatus("PENDING_CANCEL");
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

    // Get the original order to get current values
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

    // Set new quantity or use original
    int effectiveQty = request.getNewQuantity() != null
        ? request.getNewQuantity()
        : originalOrder.getQuantity();
    amendRequest.set(new OrderQty(effectiveQty));

    // Set new price or use original
    BigDecimal effectivePrice = request.getNewPrice() != null
        ? request.getNewPrice()
        : originalOrder.getPrice();
    if (effectivePrice != null) {
      amendRequest.set(new Price(effectivePrice.doubleValue()));
    }

    Session.sendToTarget(amendRequest, sessionId);

    // Create response for the new (amended) order
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

    // Track the new order
    sentOrders.put(clOrdId, response);

    log.info("Sent amend request: {} for original order {} newQty={} newPrice={}",
        clOrdId, request.getOriginalClOrdId(), effectiveQty, effectivePrice);

    return response;
  }

  /**
   * Update order status based on execution report.
   * Called by ClientFixApplication when execution reports are received.
   */
  public void updateOrderStatus(String clOrdId, String status, int filledQty, int leavesQty) {
    OrderResponse order = sentOrders.get(clOrdId);
    if (order != null) {
      order.setStatus(status);
      order.setFilledQuantity(filledQty);
      order.setLeavesQuantity(leavesQty);
      log.debug("Updated order {} status to {}", clOrdId, status);
    }
  }

  /**
   * Handle order replacement - remove old order and update new order status
   */
  public void handleOrderReplaced(String origClOrdId, String newClOrdId, String status,
                                  int filledQty, int leavesQty) {
    // Mark old order as replaced
    OrderResponse oldOrder = sentOrders.get(origClOrdId);
    if (oldOrder != null) {
      oldOrder.setStatus("REPLACED");
    }

    // Update new order
    updateOrderStatus(newClOrdId, status, filledQty, leavesQty);

    log.debug("Order replaced: {} -> {}", origClOrdId, newClOrdId);
  }

  /**
   * Remove an order from tracking (e.g., when fully filled or cancelled)
   */
  public void removeOrder(String clOrdId) {
    sentOrders.remove(clOrdId);
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

  public void updateOrderStatusForCancel(String origClOrdId, String status, int filledQty, int leavesQty) {
    OrderResponse order = sentOrders.get(origClOrdId);
    if (order != null) {
      order.setStatus(status);
      order.setFilledQuantity(filledQty);
      order.setLeavesQuantity(leavesQty);
      log.debug("Updated ORIGINAL order {} status to {}", origClOrdId, status);
    } else {
      log.warn("Cancel update: original order {} not found in sentOrders", origClOrdId);
    }
  }


  public List<OrderResponse> getSentOrders() {
    return new ArrayList<>(sentOrders.values());
  }

  /**
   * Get only open orders (not filled, cancelled, or replaced)
   */
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
    return sentOrders.get(clOrdId);
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

