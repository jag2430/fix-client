package com.example.client.service;

import com.example.client.model.MarketDataUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Finnhub market data service implementation.
 * Provides real-time quotes via REST API and WebSocket streaming for US equities.
 *
 * Free tier limits:
 * - REST API: 60 calls/minute with API key
 * - WebSocket: Real-time trades for US stocks, forex, crypto (free)
 *
 * Get your free API key at: https://finnhub.io/register
 *
 * Enable by setting market-data.provider=finnhub in application.yml
 */
@Slf4j
@Service
@EnableScheduling
@ConditionalOnProperty(name = "market-data.provider", havingValue = "finnhub")
public class FinnhubMarketDataService implements MarketDataService {

  private static final String FINNHUB_REST_URL = "https://finnhub.io/api/v1";
  private static final String FINNHUB_WS_URL = "wss://ws.finnhub.io";
  private static final String REDIS_QUOTE_PREFIX = "marketdata:quote:";
  private static final String REDIS_TRADE_PREFIX = "marketdata:trade:";
  private static final String REDIS_SUBSCRIPTIONS_KEY = "marketdata:subscriptions";

  @Value("${market-data.finnhub.api-key:}")
  private String apiKey;

  @Value("${market-data.finnhub.use-websocket:true}")
  private boolean useWebSocket;

  @Value("${market-data.finnhub.refresh-interval-ms:5000}")
  private long refreshIntervalMs;

  @Value("${market-data.finnhub.cache-ttl-seconds:300}")
  private long cacheTtlSeconds;

  private final ObjectMapper objectMapper;
  private final PositionService positionService;
  private final RedisPublisherService publisherService;
  private final RedisTemplate<String, Object> redisTemplate;

  private final HttpClient httpClient;
  private WebSocket webSocket;
  private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
  private final List<Consumer<MarketDataUpdate>> callbacks = new CopyOnWriteArrayList<>();
  private final Map<String, MarketDataUpdate> latestQuotes = new ConcurrentHashMap<>();
  private final Map<String, MarketDataUpdate> latestTrades = new ConcurrentHashMap<>();

  private volatile boolean connected = false;
  private volatile boolean wsConnected = false;
  private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();

  public FinnhubMarketDataService(ObjectMapper objectMapper,
                                  PositionService positionService,
                                  RedisPublisherService publisherService,
                                  RedisTemplate<String, Object> redisTemplate) {
    this.objectMapper = objectMapper;
    this.positionService = positionService;
    this.publisherService = publisherService;
    this.redisTemplate = redisTemplate;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  @PostConstruct
  public void init() {
    if (apiKey == null || apiKey.isEmpty()) {
      log.warn("=================================================");
      log.warn("Finnhub API key not configured!");
      log.warn("Set FINNHUB_API_KEY environment variable or");
      log.warn("market-data.finnhub.api-key in application.yml");
      log.warn("Get your FREE API key at: https://finnhub.io/register");
      log.warn("=================================================");
      return;
    }

    log.info("Finnhub market data service initializing...");
    log.info("WebSocket streaming: {}", useWebSocket ? "enabled" : "disabled");
    connect();
    loadSubscriptionsFromRedis();
  }

  @PreDestroy
  public void cleanup() {
    disconnect();
    reconnectExecutor.shutdown();
  }

  @Override
  public void connect() {
    if (apiKey == null || apiKey.isEmpty()) {
      log.error("Cannot connect: Finnhub API key not configured");
      return;
    }

    connected = true;

    if (useWebSocket) {
      connectWebSocket();
    }

    log.info("Finnhub market data service connected");
  }

  @Override
  public void disconnect() {
    connected = false;
    wsConnected = false;

    if (webSocket != null) {
      webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client disconnect");
    }

    log.info("Finnhub market data service disconnected");
  }

  @Override
  public boolean isConnected() {
    return connected;
  }

  @Override
  public void subscribeQuotes(List<String> symbols) {
    if (symbols == null || symbols.isEmpty()) return;

    List<String> upperSymbols = symbols.stream()
        .map(String::toUpperCase)
        .toList();

    subscribedSymbols.addAll(upperSymbols);
    saveSubscriptionsToRedis();

    // Subscribe via WebSocket if connected
    if (useWebSocket && wsConnected) {
      for (String symbol : upperSymbols) {
        subscribeWebSocket(symbol);
      }
    }

    // Fetch initial quotes via REST and publish them
    for (String symbol : upperSymbols) {
      fetchQuote(symbol);
    }

    log.info("Subscribed to Finnhub quotes: {}", upperSymbols);
  }

  @Override
  public void subscribeTrades(List<String> symbols) {
    // Trades come through WebSocket, quotes endpoint for REST
    subscribeQuotes(symbols);
  }

  @Override
  public void subscribeBars(List<String> symbols) {
    // Finnhub free tier doesn't support real-time bars
    // Use quotes instead
    subscribeQuotes(symbols);
  }

  @Override
  public void unsubscribe(List<String> symbols) {
    if (symbols == null) return;

    for (String symbol : symbols) {
      String upperSymbol = symbol.toUpperCase();
      subscribedSymbols.remove(upperSymbol);

      // Unsubscribe from WebSocket
      if (useWebSocket && wsConnected && webSocket != null) {
        try {
          String message = String.format("{\"type\":\"unsubscribe\",\"symbol\":\"%s\"}", upperSymbol);
          webSocket.sendText(message, true);
        } catch (Exception e) {
          log.warn("Failed to unsubscribe {} from WebSocket: {}", upperSymbol, e.getMessage());
        }
      }
    }

    saveSubscriptionsToRedis();
    log.info("Unsubscribed from Finnhub: {}", symbols);
  }

  @Override
  public MarketDataUpdate getLatestQuote(String symbol) {
    String upperSymbol = symbol.toUpperCase();

    // Check memory cache first
    MarketDataUpdate cached = latestQuotes.get(upperSymbol);
    if (cached != null) {
      return cached;
    }

    // Check Redis cache
    try {
      Object redisData = redisTemplate.opsForValue().get(REDIS_QUOTE_PREFIX + upperSymbol);
      if (redisData instanceof MarketDataUpdate) {
        MarketDataUpdate update = (MarketDataUpdate) redisData;
        latestQuotes.put(upperSymbol, update);
        return update;
      }
    } catch (Exception e) {
      log.warn("Failed to get quote from Redis for {}: {}", upperSymbol, e.getMessage());
    }

    // Fetch fresh data if subscribed
    if (subscribedSymbols.contains(upperSymbol)) {
      fetchQuote(upperSymbol);
      return latestQuotes.get(upperSymbol);
    }

    return null;
  }

  @Override
  public MarketDataUpdate getLatestTrade(String symbol) {
    return latestTrades.get(symbol.toUpperCase());
  }

  @Override
  public void registerCallback(Consumer<MarketDataUpdate> callback) {
    callbacks.add(callback);
  }

  @Override
  public String getProviderName() {
    return "Finnhub";
  }

  /**
   * Scheduled task to refresh quotes for subscribed symbols via REST API.
   * Runs periodically to keep quotes fresh, especially when markets are open.
   */
  @Scheduled(fixedDelayString = "${market-data.finnhub.refresh-interval-ms:5000}")
  public void refreshSubscribedQuotes() {
    if (!connected || subscribedSymbols.isEmpty()) {
      return;
    }

    // If WebSocket is enabled and connected, we still do periodic REST refreshes
    // to ensure we have latest quote data (WebSocket only gives trades)

    // Rate limit: 60 calls/minute = 1 call per second
    // Batch symbols to avoid hitting rate limits
    int delay = 0;
    for (String symbol : subscribedSymbols) {
      final String sym = symbol;
      reconnectExecutor.schedule(() -> fetchQuote(sym), delay, TimeUnit.MILLISECONDS);
      delay += 1100; // 1.1 seconds between calls to stay under rate limit
    }
  }

  /**
   * Fetch quote from Finnhub REST API
   */
  private void fetchQuote(String symbol) {
    if (apiKey == null || apiKey.isEmpty()) return;

    try {
      String url = String.format("%s/quote?symbol=%s&token=%s",
          FINNHUB_REST_URL, symbol, apiKey);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Accept", "application/json")
          .GET()
          .timeout(Duration.ofSeconds(10))
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        parseQuoteResponse(symbol, response.body());
      } else if (response.statusCode() == 429) {
        log.warn("Finnhub rate limit reached. Consider reducing refresh interval.");
      } else {
        log.warn("Finnhub API returned status {}: {}",
            response.statusCode(), response.body());
      }

    } catch (Exception e) {
      log.error("Failed to fetch quote from Finnhub for {}: {}", symbol, e.getMessage());
    }
  }

  /**
   * Parse quote response from REST API
   * Response format: {"c":150.0,"d":1.5,"dp":1.0,"h":151.0,"l":149.0,"o":149.5,"pc":148.5,"t":1234567890}
   */
  private void parseQuoteResponse(String symbol, String jsonResponse) {
    try {
      JsonNode root = objectMapper.readTree(jsonResponse);

      // Check for error response
      if (root.has("error")) {
        log.warn("Finnhub error for {}: {}", symbol, root.path("error").asText());
        return;
      }

      BigDecimal currentPrice = getBigDecimal(root, "c");   // Current price
      BigDecimal change = getBigDecimal(root, "d");          // Change
      BigDecimal changePercent = getBigDecimal(root, "dp");  // Change percent
      BigDecimal high = getBigDecimal(root, "h");            // High
      BigDecimal low = getBigDecimal(root, "l");             // Low
      BigDecimal open = getBigDecimal(root, "o");            // Open
      BigDecimal previousClose = getBigDecimal(root, "pc");  // Previous close

      if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
        log.debug("No price data for {}", symbol);
        return;
      }

      MarketDataUpdate quoteUpdate = MarketDataUpdate.builder()
          .symbol(symbol)
          .price(currentPrice)
          .open(open)
          .high(high)
          .low(low)
          .previousClose(previousClose)
          .change(change)
          .changePercent(changePercent)
          .source("finnhub")
          .updateType("QUOTE")
          .timestamp(LocalDateTime.now())
          .build();

      processUpdate(symbol, quoteUpdate);

    } catch (Exception e) {
      log.error("Failed to parse Finnhub quote response for {}", symbol, e);
    }
  }

  /**
   * Connect to Finnhub WebSocket for real-time trades
   */
  private void connectWebSocket() {
    if (apiKey == null || apiKey.isEmpty()) {
      log.error("Cannot connect WebSocket: API key not configured");
      return;
    }

    String wsUrl = FINNHUB_WS_URL + "?token=" + apiKey;
    log.info("Connecting to Finnhub WebSocket...");

    try {
      CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
          .buildAsync(URI.create(wsUrl), new FinnhubWebSocketListener());

      webSocket = wsFuture.get(10, TimeUnit.SECONDS);

    } catch (Exception e) {
      log.error("Failed to connect to Finnhub WebSocket", e);
      scheduleReconnect();
    }
  }

  /**
   * Subscribe to a symbol via WebSocket
   */
  private void subscribeWebSocket(String symbol) {
    if (webSocket != null && wsConnected) {
      try {
        String message = String.format("{\"type\":\"subscribe\",\"symbol\":\"%s\"}", symbol);
        webSocket.sendText(message, true);
        log.debug("WebSocket subscribed to {}", symbol);
      } catch (Exception e) {
        log.warn("Failed to subscribe {} via WebSocket: {}", symbol, e.getMessage());
      }
    }
  }

  /**
   * Process and distribute market data update.
   * ALWAYS publishes to Redis and updates positions - not just on price change.
   */
  private void processUpdate(String symbol, MarketDataUpdate update) {
    MarketDataUpdate previousQuote = latestQuotes.put(symbol, update);

    if ("TRADE".equals(update.getUpdateType())) {
      latestTrades.put(symbol, update);
    }

    // Save to Redis cache
    saveToRedis(symbol, update);

    // ALWAYS update position with latest price
    if (update.getPrice() != null) {
      positionService.updateMarketPrice(symbol, update.getPrice());
    }

    // ALWAYS publish to Redis pub/sub so dashboard receives updates
    publisherService.publishMarketDataUpdate(update);

    // Notify callbacks
    for (Consumer<MarketDataUpdate> callback : callbacks) {
      try {
        callback.accept(update);
      } catch (Exception e) {
        log.error("Callback error for {}", symbol, e);
      }
    }

    // Log if price changed
    if (previousQuote == null) {
      log.info("Quote received: {} @ ${}", symbol, update.getPrice());
    } else if (!Objects.equals(previousQuote.getPrice(), update.getPrice())) {
      log.info("Quote updated: {} @ ${} (was ${})",
          symbol, update.getPrice(), previousQuote.getPrice());
    } else {
      log.debug("Quote refreshed: {} @ ${} (unchanged)", symbol, update.getPrice());
    }
  }

  /**
   * Save market data to Redis
   */
  private void saveToRedis(String symbol, MarketDataUpdate update) {
    try {
      String prefix = "TRADE".equals(update.getUpdateType()) ?
          REDIS_TRADE_PREFIX : REDIS_QUOTE_PREFIX;

      redisTemplate.opsForValue().set(
          prefix + symbol,
          update,
          Duration.ofSeconds(cacheTtlSeconds)
      );
    } catch (Exception e) {
      log.warn("Failed to save market data to Redis for {}: {}", symbol, e.getMessage());
    }
  }

  /**
   * Save current subscriptions to Redis for persistence
   */
  private void saveSubscriptionsToRedis() {
    try {
      redisTemplate.delete(REDIS_SUBSCRIPTIONS_KEY);
      if (!subscribedSymbols.isEmpty()) {
        redisTemplate.opsForSet().add(REDIS_SUBSCRIPTIONS_KEY,
            subscribedSymbols.toArray(new String[0]));
      }
    } catch (Exception e) {
      log.warn("Failed to save subscriptions to Redis: {}", e.getMessage());
    }
  }

  /**
   * Load subscriptions from Redis on startup
   */
  private void loadSubscriptionsFromRedis() {
    try {
      Set<Object> saved = redisTemplate.opsForSet().members(REDIS_SUBSCRIPTIONS_KEY);
      if (saved != null && !saved.isEmpty()) {
        List<String> symbols = new ArrayList<>();
        for (Object obj : saved) {
          symbols.add(obj.toString().toUpperCase());
        }
        log.info("Loaded {} subscriptions from Redis: {}", symbols.size(), symbols);

        // Re-subscribe (this will fetch fresh quotes and publish them)
        subscribeQuotes(symbols);
      }
    } catch (Exception e) {
      log.warn("Failed to load subscriptions from Redis: {}", e.getMessage());
    }
  }

  /**
   * Schedule WebSocket reconnection
   */
  private void scheduleReconnect() {
    reconnectExecutor.schedule(() -> {
      if (connected && !wsConnected) {
        log.info("Attempting to reconnect to Finnhub WebSocket...");
        connectWebSocket();
      }
    }, 5, TimeUnit.SECONDS);
  }

  /**
   * Get all subscribed symbols
   */
  public Set<String> getSubscribedSymbols() {
    return new HashSet<>(subscribedSymbols);
  }

  /**
   * Get all cached quotes
   */
  public Map<String, MarketDataUpdate> getAllCachedQuotes() {
    return new HashMap<>(latestQuotes);
  }

  /**
   * Check if WebSocket is connected
   */
  public boolean isWebSocketConnected() {
    return wsConnected;
  }

  /**
   * Helper to safely get BigDecimal from JSON
   */
  private BigDecimal getBigDecimal(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    try {
      return BigDecimal.valueOf(value.asDouble()).setScale(4, RoundingMode.HALF_UP);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * WebSocket listener for Finnhub real-time trades
   */
  private class FinnhubWebSocketListener implements WebSocket.Listener {

    private final StringBuilder messageBuffer = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      log.info("Finnhub WebSocket connected");
      wsConnected = true;

      // Re-subscribe all symbols
      for (String symbol : subscribedSymbols) {
        subscribeWebSocket(symbol);
      }

      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      messageBuffer.append(data);

      if (last) {
        handleWebSocketMessage(messageBuffer.toString());
        messageBuffer.setLength(0);
      }

      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      log.info("Finnhub WebSocket closed: {} - {}", statusCode, reason);
      wsConnected = false;
      scheduleReconnect();
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      log.error("Finnhub WebSocket error", error);
      wsConnected = false;
      scheduleReconnect();
    }
  }

  /**
   * Handle WebSocket message
   * Trade message format: {"data":[{"p":150.0,"s":"AAPL","t":1234567890123,"v":100}],"type":"trade"}
   * Ping message: {"type":"ping"}
   */
  private void handleWebSocketMessage(String message) {
    try {
      JsonNode root = objectMapper.readTree(message);
      String type = root.path("type").asText();

      if ("trade".equals(type)) {
        JsonNode dataArray = root.path("data");
        if (dataArray.isArray()) {
          for (JsonNode trade : dataArray) {
            processTrade(trade);
          }
        }
      } else if ("ping".equals(type)) {
        // Finnhub sends pings to keep connection alive
        log.trace("Received ping from Finnhub");
      } else if ("error".equals(type)) {
        log.error("Finnhub WebSocket error: {}", root.path("msg").asText());
      }

    } catch (Exception e) {
      log.error("Failed to parse WebSocket message: {}", message, e);
    }
  }

  /**
   * Process a trade from WebSocket
   */
  private void processTrade(JsonNode trade) {
    try {
      String symbol = trade.path("s").asText();
      BigDecimal price = getBigDecimal(trade, "p");
      BigDecimal volume = getBigDecimal(trade, "v");
      long timestamp = trade.path("t").asLong();

      if (symbol == null || symbol.isEmpty() || price == null) {
        return;
      }

      MarketDataUpdate tradeUpdate = MarketDataUpdate.builder()
          .symbol(symbol)
          .price(price)
          .volume(volume)
          .source("finnhub")
          .updateType("TRADE")
          .timestamp(LocalDateTime.now())
          .build();

      processUpdate(symbol, tradeUpdate);

    } catch (Exception e) {
      log.error("Failed to process trade", e);
    }
  }
}