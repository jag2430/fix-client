package com.example.client.controller;

import com.example.client.model.MarketDataUpdate;
import com.example.client.model.Position;
import com.example.client.model.PortfolioSummary;
import com.example.client.service.MarketDataService;
import com.example.client.service.PositionService;
import com.example.client.service.YahooFinanceMarketDataService;
import com.example.client.websocket.PortfolioWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

  private final PositionService positionService;
  private final MarketDataService marketDataService;
  private final PortfolioWebSocketHandler webSocketHandler;

  // =========================================================================
  // Position Endpoints
  // =========================================================================

  @GetMapping("/positions")
  public ResponseEntity<List<Position>> getAllPositions(
      @RequestParam(required = false, defaultValue = "false") boolean openOnly) {
    if (openOnly) {
      return ResponseEntity.ok(positionService.getOpenPositions());
    }
    return ResponseEntity.ok(positionService.getAllPositions());
  }

  @GetMapping("/positions/{symbol}")
  public ResponseEntity<?> getPosition(@PathVariable String symbol) {
    Position position = positionService.getPosition(symbol.toUpperCase());
    if (position == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(position);
  }

  @GetMapping("/summary")
  public ResponseEntity<PortfolioSummary> getPortfolioSummary() {
    return ResponseEntity.ok(positionService.getPortfolioSummary());
  }

  @DeleteMapping("/positions")
  public ResponseEntity<Map<String, String>> clearPositions() {
    positionService.clearAllPositions();
    return ResponseEntity.ok(Map.of("message", "Positions cleared"));
  }

  // =========================================================================
  // Market Data Endpoints
  // =========================================================================

  @GetMapping("/market-data/{symbol}")
  public ResponseEntity<?> getMarketData(@PathVariable String symbol) {
    String upperSymbol = symbol.toUpperCase();

    MarketDataUpdate quote = marketDataService.getLatestQuote(upperSymbol);
    MarketDataUpdate trade = marketDataService.getLatestTrade(upperSymbol);

    if (quote == null && trade == null) {
      return ResponseEntity.ok(Map.of(
          "symbol", upperSymbol,
          "message", "No market data available. Try subscribing first.",
          "provider", marketDataService.getProviderName()
      ));
    }

    Map<String, Object> response = new HashMap<>();
    response.put("symbol", upperSymbol);
    response.put("provider", marketDataService.getProviderName());

    if (quote != null) {
      response.put("quote", quote);
    }
    if (trade != null) {
      response.put("trade", trade);
    }

    return ResponseEntity.ok(response);
  }

  @GetMapping("/market-data")
  public ResponseEntity<?> getAllMarketData() {
    Map<String, Object> response = new HashMap<>();
    response.put("provider", marketDataService.getProviderName());
    response.put("connected", marketDataService.isConnected());

    // If Yahoo Finance service, get all cached quotes
    if (marketDataService instanceof YahooFinanceMarketDataService yahooService) {
      response.put("subscriptions", yahooService.getSubscribedSymbols());
      response.put("quotes", yahooService.getAllCachedQuotes());
    }

    return ResponseEntity.ok(response);
  }

  @PostMapping("/market-data/subscribe")
  public ResponseEntity<?> subscribeMarketData(@RequestBody Map<String, List<String>> request) {
    List<String> symbols = request.get("symbols");
    if (symbols == null || symbols.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of(
          "error", "Missing 'symbols' array in request body"
      ));
    }

    List<String> upperSymbols = symbols.stream()
        .map(String::toUpperCase)
        .toList();

    marketDataService.subscribeTrades(upperSymbols);
    marketDataService.subscribeQuotes(upperSymbols);

    return ResponseEntity.ok(Map.of(
        "message", "Subscribed to market data",
        "symbols", upperSymbols,
        "provider", marketDataService.getProviderName()
    ));
  }

  @PostMapping("/market-data/unsubscribe")
  public ResponseEntity<?> unsubscribeMarketData(@RequestBody Map<String, List<String>> request) {
    List<String> symbols = request.get("symbols");
    if (symbols == null || symbols.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of(
          "error", "Missing 'symbols' array in request body"
      ));
    }

    marketDataService.unsubscribe(symbols);

    return ResponseEntity.ok(Map.of(
        "message", "Unsubscribed from market data",
        "symbols", symbols
    ));
  }

  @GetMapping("/market-data/status")
  public ResponseEntity<Map<String, Object>> getMarketDataStatus() {
    Map<String, Object> status = new HashMap<>();
    status.put("provider", marketDataService.getProviderName());
    status.put("connected", marketDataService.isConnected());

    // Add Yahoo Finance specific info
    if (marketDataService instanceof YahooFinanceMarketDataService yahooService) {
      Set<String> subscriptions = yahooService.getSubscribedSymbols();
      status.put("subscribedSymbols", subscriptions);
      status.put("subscriptionCount", subscriptions.size());
    }

    return ResponseEntity.ok(status);
  }

  @GetMapping("/market-data/subscriptions")
  public ResponseEntity<?> getSubscriptions() {
    if (marketDataService instanceof YahooFinanceMarketDataService yahooService) {
      return ResponseEntity.ok(Map.of(
          "provider", marketDataService.getProviderName(),
          "subscriptions", yahooService.getSubscribedSymbols()
      ));
    }

    return ResponseEntity.ok(Map.of(
        "provider", marketDataService.getProviderName(),
        "message", "Subscription tracking not available for this provider"
    ));
  }

  // =========================================================================
  // Manual Price Update (for testing without market data provider)
  // =========================================================================

  @PostMapping("/positions/{symbol}/price")
  public ResponseEntity<?> updatePrice(
      @PathVariable String symbol,
      @RequestBody Map<String, BigDecimal> request) {
    BigDecimal price = request.get("price");
    if (price == null) {
      return ResponseEntity.badRequest().body(Map.of(
          "error", "Missing 'price' in request body"
      ));
    }

    Position position = positionService.updateMarketPrice(symbol.toUpperCase(), price);
    if (position == null) {
      return ResponseEntity.notFound().build();
    }

    return ResponseEntity.ok(position);
  }

  // =========================================================================
  // Batch Quote Request (convenience endpoint)
  // =========================================================================

  @PostMapping("/market-data/quotes")
  public ResponseEntity<?> getQuotes(@RequestBody Map<String, List<String>> request) {
    List<String> symbols = request.get("symbols");
    if (symbols == null || symbols.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of(
          "error", "Missing 'symbols' array in request body"
      ));
    }

    Map<String, MarketDataUpdate> quotes = new HashMap<>();
    for (String symbol : symbols) {
      String upperSymbol = symbol.toUpperCase();
      MarketDataUpdate quote = marketDataService.getLatestQuote(upperSymbol);
      if (quote != null) {
        quotes.put(upperSymbol, quote);
      }
    }

    return ResponseEntity.ok(Map.of(
        "provider", marketDataService.getProviderName(),
        "quotes", quotes,
        "requestedCount", symbols.size(),
        "foundCount", quotes.size()
    ));
  }

  // =========================================================================
  // WebSocket Info
  // =========================================================================

  @GetMapping("/websocket/info")
  public ResponseEntity<Map<String, Object>> getWebSocketInfo() {
    return ResponseEntity.ok(Map.of(
        "endpoint", "/ws/portfolio",
        "connectedClients", webSocketHandler.getConnectedClientCount(),
        "channels", Map.of(
            "positions", "positions:updates",
            "executions", "executions:updates",
            "orders", "orders:updates",
            "marketdata", "marketdata:updates"
        ),
        "actions", List.of(
            Map.of("action", "subscribe", "params", Map.of("channel", "string")),
            Map.of("action", "unsubscribe", "params", Map.of("channel", "string")),
            Map.of("action", "getPortfolio", "params", Map.of()),
            Map.of("action", "ping", "params", Map.of())
        )
    ));
  }
}