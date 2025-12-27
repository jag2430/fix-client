package com.example.client.controller;

import com.example.client.model.*;
import com.example.client.service.ExecutionService;
import com.example.client.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OrderController {

  private final OrderService orderService;
  private final ExecutionService executionService;

  // =========================================================================
  // Order Endpoints
  // =========================================================================

  @PostMapping("/orders")
  public ResponseEntity<?> sendOrder(@RequestBody OrderRequest request) {
    try {
      log.info("Received order request: {}", request);
      OrderResponse response = orderService.sendOrder(request);
      return ResponseEntity.ok(response);
    } catch (IllegalStateException e) {
      log.error("No active session: {}", e.getMessage());
      return ResponseEntity.badRequest().body(Map.of(
          "error", "No active FIX session",
          "message", e.getMessage()
      ));
    } catch (IllegalArgumentException e) {
      log.error("Invalid request: {}", e.getMessage());
      return ResponseEntity.badRequest().body(Map.of(
          "error", "Invalid request",
          "message", e.getMessage()
      ));
    } catch (Exception e) {
      log.error("Error sending order", e);
      return ResponseEntity.internalServerError().body(Map.of(
          "error", "Failed to send order",
          "message", e.getMessage()
      ));
    }
  }

  @DeleteMapping("/orders/{clOrdId}")
  public ResponseEntity<?> cancelOrder(
      @PathVariable String clOrdId,
      @RequestParam String symbol,
      @RequestParam String side) {
    try {
      CancelRequest request = CancelRequest.builder()
          .originalClOrdId(clOrdId)
          .symbol(symbol)
          .side(side)
          .build();

      log.info("Received cancel request for order: {}", clOrdId);
      OrderResponse response = orderService.cancelOrder(request);
      return ResponseEntity.ok(response);
    } catch (IllegalStateException e) {
      log.error("No active session: {}", e.getMessage());
      return ResponseEntity.badRequest().body(Map.of(
          "error", "No active FIX session",
          "message", e.getMessage()
      ));
    } catch (Exception e) {
      log.error("Error cancelling order", e);
      return ResponseEntity.internalServerError().body(Map.of(
          "error", "Failed to cancel order",
          "message", e.getMessage()
      ));
    }
  }

  @PutMapping("/orders/{clOrdId}")
  public ResponseEntity<?> amendOrder(
      @PathVariable String clOrdId,
      @RequestBody AmendRequest request) {
    try {
      // Set the original order ID from path
      request.setOriginalClOrdId(clOrdId);

      log.info("Received amend request for order: {} newQty={} newPrice={}",
          clOrdId, request.getNewQuantity(), request.getNewPrice());

      OrderResponse response = orderService.amendOrder(request);
      return ResponseEntity.ok(response);
    } catch (IllegalStateException e) {
      log.error("No active session: {}", e.getMessage());
      return ResponseEntity.badRequest().body(Map.of(
          "error", "No active FIX session",
          "message", e.getMessage()
      ));
    } catch (IllegalArgumentException e) {
      log.error("Invalid request: {}", e.getMessage());
      return ResponseEntity.badRequest().body(Map.of(
          "error", "Invalid request",
          "message", e.getMessage()
      ));
    } catch (Exception e) {
      log.error("Error amending order", e);
      return ResponseEntity.internalServerError().body(Map.of(
          "error", "Failed to amend order",
          "message", e.getMessage()
      ));
    }
  }

  @GetMapping("/orders")
  public ResponseEntity<List<OrderResponse>> getSentOrders(
      @RequestParam(required = false, defaultValue = "false") boolean openOnly) {
    if (openOnly) {
      return ResponseEntity.ok(orderService.getOpenOrders());
    }
    return ResponseEntity.ok(orderService.getSentOrders());
  }

  @GetMapping("/orders/open")
  public ResponseEntity<List<OrderResponse>> getOpenOrders() {
    return ResponseEntity.ok(orderService.getOpenOrders());
  }

  @GetMapping("/orders/{clOrdId}")
  public ResponseEntity<?> getOrder(@PathVariable String clOrdId) {
    OrderResponse order = orderService.getOrderByClOrdId(clOrdId);
    if (order == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(order);
  }

  // =========================================================================
  // Execution Endpoints
  // =========================================================================

  @GetMapping("/executions")
  public ResponseEntity<List<ExecutionMessage>> getExecutions(
      @RequestParam(required = false) String clOrdId,
      @RequestParam(required = false, defaultValue = "100") int limit) {

    if (clOrdId != null) {
      return ResponseEntity.ok(executionService.getExecutionsByClOrdId(clOrdId));
    }
    return ResponseEntity.ok(executionService.getRecentExecutions(limit));
  }

  @GetMapping("/executions/count")
  public ResponseEntity<Map<String, Integer>> getExecutionCount() {
    return ResponseEntity.ok(Map.of("count", executionService.getExecutionCount()));
  }

  @DeleteMapping("/executions")
  public ResponseEntity<Map<String, String>> clearExecutions() {
    executionService.clearExecutions();
    return ResponseEntity.ok(Map.of("message", "Executions cleared"));
  }

  // =========================================================================
  // Session Endpoints
  // =========================================================================

  @GetMapping("/sessions")
  public ResponseEntity<List<SessionStatus>> getSessionStatus() {
    return ResponseEntity.ok(orderService.getSessionStatus());
  }

  // =========================================================================
  // Health Check
  // =========================================================================

  @GetMapping("/health")
  public ResponseEntity<Map<String, Object>> healthCheck() {
    List<SessionStatus> sessions = orderService.getSessionStatus();
    boolean anyLoggedOn = sessions.stream().anyMatch(SessionStatus::isLoggedOn);

    return ResponseEntity.ok(Map.of(
        "status", anyLoggedOn ? "UP" : "DOWN",
        "fixConnected", anyLoggedOn,
        "sessions", sessions,
        "executionCount", executionService.getExecutionCount(),
        "openOrderCount", orderService.getOpenOrders().size()
    ));
  }
}
