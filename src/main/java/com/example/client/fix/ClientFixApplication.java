package com.example.client.fix;

import com.example.client.model.ExecutionMessage;
import com.example.client.service.ExecutionService;
import com.example.client.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Component
public class ClientFixApplication implements Application {

  private final ExecutionService executionService;
  private final OrderService orderService;

  // Use @Lazy for OrderService to break circular dependency
  public ClientFixApplication(ExecutionService executionService, @Lazy OrderService orderService) {
    this.executionService = executionService;
    this.orderService = orderService;
  }

  @Override
  public void onCreate(SessionID sessionId) {
    log.info("Session created: {}", sessionId);
  }

  @Override
  public void onLogon(SessionID sessionId) {
    log.info("Logged on to session: {}", sessionId);
  }

  @Override
  public void onLogout(SessionID sessionId) {
    log.info("Logged out from session: {}", sessionId);
  }

  @Override
  public void toAdmin(Message message, SessionID sessionId) {
    try {
      String msgType = message.getHeader().getString(MsgType.FIELD);
      if (MsgType.LOGON.equals(msgType)) {
        log.debug("Sending logon request to {}", sessionId);
      }
    } catch (FieldNotFound e) {
      log.error("Error in toAdmin", e);
    }
  }

  @Override
  public void fromAdmin(Message message, SessionID sessionId)
      throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
    try {
      String msgType = message.getHeader().getString(MsgType.FIELD);
      log.debug("Received admin message: {} from {}", msgType, sessionId);
    } catch (FieldNotFound e) {
      log.error("Error in fromAdmin", e);
    }
  }

  @Override
  public void toApp(Message message, SessionID sessionId) throws DoNotSend {
    log.debug("Sending application message: {}", message);
  }

  @Override
  public void fromApp(Message message, SessionID sessionId)
      throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {

    log.debug("Received application message: {}", message);

    try {
      String msgType = message.getHeader().getString(MsgType.FIELD);

      if (MsgType.EXECUTION_REPORT.equals(msgType)) {
        handleExecutionReport((ExecutionReport) message, sessionId);
      } else {
        log.warn("Received unhandled message type: {}", msgType);
      }
    } catch (Exception e) {
      log.error("Error processing message", e);
    }
  }

  private void handleExecutionReport(ExecutionReport report, SessionID sessionId)
      throws FieldNotFound {

    String clOrdId = report.getClOrdID().getValue();
    char execTypeChar = report.getExecType().getValue();
    String execTypeStr = getExecTypeString(execTypeChar);
    String orderStatusStr = getOrderStatusString(report.getOrdStatus().getValue());
    int leavesQty = (int) report.getLeavesQty().getValue();
    int cumQty = (int) report.getCumQty().getValue();

    // Get origClOrdId if present (for cancel/replace)
    String origClOrdId = null;
    if (report.isSetOrigClOrdID()) {
      origClOrdId = report.getOrigClOrdID().getValue();
    }

    ExecutionMessage execMessage = ExecutionMessage.builder()
        .execId(report.getExecID().getValue())
        .orderId(report.getOrderID().getValue())
        .clOrdId(clOrdId)
        .origClOrdId(origClOrdId)
        .symbol(report.getSymbol().getValue())
        .side(report.getSide().getValue() == Side.BUY ? "BUY" : "SELL")
        .execType(execTypeStr)
        .orderStatus(orderStatusStr)
        .lastPrice(report.isSetLastPx()
            ? BigDecimal.valueOf(report.getLastPx().getValue())
            : BigDecimal.ZERO)
        .lastQuantity(report.isSetLastQty()
            ? (int) report.getLastQty().getValue()
            : 0)
        .leavesQuantity(leavesQty)
        .cumQuantity(cumQty)
        .avgPrice(BigDecimal.valueOf(report.getAvgPx().getValue()))
        .timestamp(LocalDateTime.now())
        .build();

    log.info("Execution Report: {} {} {} - Type: {} Status: {} LastQty: {} @ {}",
        execMessage.getSide(),
        execMessage.getSymbol(),
        execMessage.getClOrdId(),
        execMessage.getExecType(),
        execMessage.getOrderStatus(),
        execMessage.getLastQuantity(),
        execMessage.getLastPrice());

    // Store execution
    executionService.addExecution(execMessage);

    // Update order status in OrderService
    if (execTypeChar == ExecType.REPLACED && origClOrdId != null) {

      orderService.handleOrderReplaced(origClOrdId, clOrdId, orderStatusStr, cumQty, leavesQty);

    } else if ((execTypeChar == ExecType.CANCELED
        || execTypeChar == ExecType.PENDING_CANCEL
        || report.getOrdStatus().getValue() == OrdStatus.CANCELED
        || report.getOrdStatus().getValue() == OrdStatus.PENDING_CANCEL)
        && origClOrdId != null) {

      // Cancel & pending-cancel reports must update the ORIGINAL order
      orderService.updateOrderStatusForCancel(origClOrdId, orderStatusStr, cumQty, leavesQty);

    } else {

      orderService.updateOrderStatus(clOrdId, orderStatusStr, cumQty, leavesQty);
    }
  }

  private String getExecTypeString(char execType) {
    return switch (execType) {
      case ExecType.NEW -> "NEW";
      case ExecType.PARTIAL_FILL -> "PARTIAL_FILL";
      case ExecType.FILL -> "FILL";
      case ExecType.CANCELED -> "CANCELLED";
      case ExecType.REPLACED -> "REPLACED";
      case ExecType.REJECTED -> "REJECTED";
      case ExecType.PENDING_NEW -> "PENDING_NEW";
      case ExecType.PENDING_CANCEL -> "PENDING_CANCEL";
      case ExecType.PENDING_REPLACE -> "PENDING_REPLACE";
      default -> "UNKNOWN(" + execType + ")";
    };
  }

  private String getOrderStatusString(char ordStatus) {
    return switch (ordStatus) {
      case OrdStatus.NEW -> "NEW";
      case OrdStatus.PARTIALLY_FILLED -> "PARTIALLY_FILLED";
      case OrdStatus.FILLED -> "FILLED";
      case OrdStatus.CANCELED -> "CANCELLED";
      case OrdStatus.REJECTED -> "REJECTED";
      case OrdStatus.PENDING_NEW -> "PENDING_NEW";
      case OrdStatus.PENDING_CANCEL -> "PENDING_CANCEL";
      case OrdStatus.PENDING_REPLACE -> "PENDING_REPLACE";
      default -> "UNKNOWN(" + ordStatus + ")";
    };
  }
}
