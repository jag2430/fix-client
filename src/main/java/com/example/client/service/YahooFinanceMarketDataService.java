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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Yahoo Finance market data service implementation.
 * Fetches real-time quotes from Yahoo Finance API for US Equities.
 * Stores market data in Redis for persistence and fast access.
 */
@Slf4j
@Service
@EnableScheduling
@ConditionalOnProperty(name = "market-data.provider", havingValue = "yahoo")
public class YahooFinanceMarketDataService implements MarketDataService {

  private static final String YAHOO_QUOTE_URL = "https://query1.finance.yahoo.com/v7/finance/quote";
  private static final String REDIS_QUOTE_PREFIX = "marketdata:quote:";
  private static final String REDIS_TRADE_PREFIX = "marketdata:trade:";
  private static final String REDIS_SUBSCRIPTIONS_KEY = "marketdata:subscriptions";

  @Value("${market-data.yahoo.refresh-interval-ms:5000}")
  private long refreshIntervalMs;

  @Value("${market-data.yahoo.cache-ttl-seconds:300}")
  private long cacheTtlSeconds;

  private final ObjectMapper objectMapper;
  private final PositionService positionService;
  private final RedisPublisherService publisherService;
  private final RedisTemplate<String, Object> redisTemplate;

  private final HttpClient httpClient;
  private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
  private final List<Consumer<MarketDataUpdate>> callbacks = new CopyOnWriteArrayList<>();
  private final Map<String, MarketDataUpdate> latestQuotes = new ConcurrentHashMap<>();
  private final Map<String, MarketDataUpdate> latestTrades = new ConcurrentHashMap<>();

  private volatile boolean connected = false;
  private ScheduledExecutorService scheduler;

  public YahooFinanceMarketDataService(ObjectMapper objectMapper,
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
    log.info("Yahoo Finance market data service initializing...");
    connect();
    loadSubscriptionsFromRedis();
  }

  @PreDestroy
  public void cleanup() {
    disconnect();
  }

  @Override
  public void connect() {
    if (scheduler == null || scheduler.isShutdown()) {
      scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "yahoo-finance-scheduler");
        t.setDaemon(true);
        return t;
      });
    }
    connected = true;
    log.info("Yahoo Finance market data service connected");
  }

  @Override
  public void disconnect() {
    connected = false;
    if (scheduler != null && !scheduler.isShutdown()) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    log.info("Yahoo Finance market data service disconnected");
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

    // Fetch initial quotes immediately
    fetchQuotes(upperSymbols);

    log.info("Subscribed to Yahoo Finance quotes: {}", upperSymbols);
  }

  @Override
  public void subscribeTrades(List<String> symbols) {
    // Yahoo Finance doesn't provide real-time trade stream
    // We'll treat quotes as trades for simplicity
    subscribeQuotes(symbols);
  }

  @Override
  public void subscribeBars(List<String> symbols) {
    // Yahoo Finance doesn't provide real-time bars in free tier
    subscribeQuotes(symbols);
  }

  @Override
  public void unsubscribe(List<String> symbols) {
    if (symbols == null) return;

    symbols.forEach(s -> subscribedSymbols.remove(s.toUpperCase()));
    saveSubscriptionsToRedis();

    log.info("Unsubscribed from Yahoo Finance: {}", symbols);
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
      fetchQuotes(List.of(upperSymbol));
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
    return "Yahoo Finance";
  }

  /**
   * Scheduled task to refresh quotes for subscribed symbols.
   * Runs every 5 seconds by default (configurable).
   */
  @Scheduled(fixedDelayString = "${market-data.yahoo.refresh-interval-ms:5000}")
  public void refreshSubscribedQuotes() {
    if (!connected || subscribedSymbols.isEmpty()) {
      return;
    }

    fetchQuotes(new ArrayList<>(subscribedSymbols));
  }

  /**
   * Fetch quotes from Yahoo Finance API
   */
  private void fetchQuotes(List<String> symbols) {
    if (symbols == null || symbols.isEmpty()) return;

    try {
      String symbolsParam = String.join(",", symbols);
      String url = YAHOO_QUOTE_URL + "?symbols=" +
          URLEncoder.encode(symbolsParam, StandardCharsets.UTF_8);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Accept", "application/json")
          .header("User-Agent", "Mozilla/5.0")
          .GET()
          .timeout(Duration.ofSeconds(10))
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        parseAndProcessQuotes(response.body());
      } else {
        log.warn("Yahoo Finance API returned status {}: {}",
            response.statusCode(), response.body());
      }

    } catch (Exception e) {
      log.error("Failed to fetch quotes from Yahoo Finance: {}", e.getMessage());
    }
  }

  /**
   * Parse Yahoo Finance response and process quotes
   */
  private void parseAndProcessQuotes(String jsonResponse) {
    try {
      JsonNode root = objectMapper.readTree(jsonResponse);
      JsonNode quoteResponse = root.path("quoteResponse");
      JsonNode results = quoteResponse.path("result");

      if (results.isArray()) {
        for (JsonNode quote : results) {
          processQuote(quote);
        }
      }

    } catch (Exception e) {
      log.error("Failed to parse Yahoo Finance response", e);
    }
  }

  /**
   * Process a single quote from Yahoo Finance
   */
  private void processQuote(JsonNode quote) {
    try {
      String symbol = quote.path("symbol").asText();

      BigDecimal regularMarketPrice = getBigDecimal(quote, "regularMarketPrice");
      BigDecimal bid = getBigDecimal(quote, "bid");
      BigDecimal ask = getBigDecimal(quote, "ask");
      BigDecimal bidSize = getBigDecimal(quote, "bidSize");
      BigDecimal askSize = getBigDecimal(quote, "askSize");
      BigDecimal volume = getBigDecimal(quote, "regularMarketVolume");
      BigDecimal open = getBigDecimal(quote, "regularMarketOpen");
      BigDecimal high = getBigDecimal(quote, "regularMarketDayHigh");
      BigDecimal low = getBigDecimal(quote, "regularMarketDayLow");
      BigDecimal previousClose = getBigDecimal(quote, "regularMarketPreviousClose");
      BigDecimal change = getBigDecimal(quote, "regularMarketChange");
      BigDecimal changePercent = getBigDecimal(quote, "regularMarketChangePercent");

      // Create quote update
      MarketDataUpdate quoteUpdate = MarketDataUpdate.builder()
          .symbol(symbol)
          .price(regularMarketPrice)
          .bidPrice(bid)
          .askPrice(ask)
          .bidSize(bidSize)
          .askSize(askSize)
          .volume(volume)
          .open(open)
          .high(high)
          .low(low)
          .previousClose(previousClose)
          .change(change)
          .changePercent(changePercent)
          .source("yahoo")
          .updateType("QUOTE")
          .timestamp(LocalDateTime.now())
          .build();

      // Create trade update (using last price as trade)
      MarketDataUpdate tradeUpdate = MarketDataUpdate.builder()
          .symbol(symbol)
          .price(regularMarketPrice)
          .volume(volume)
          .source("yahoo")
          .updateType("TRADE")
          .timestamp(LocalDateTime.now())
          .build();

      // Update caches
      MarketDataUpdate previousQuote = latestQuotes.put(symbol, quoteUpdate);
      latestTrades.put(symbol, tradeUpdate);

      // Save to Redis
      saveToRedis(symbol, quoteUpdate, tradeUpdate);

      // Update position with latest price
      if (regularMarketPrice != null) {
        positionService.updateMarketPrice(symbol, regularMarketPrice);
      }

      // Only notify callbacks if price changed
      if (previousQuote == null ||
          !Objects.equals(previousQuote.getPrice(), quoteUpdate.getPrice())) {

        // Notify callbacks
        for (Consumer<MarketDataUpdate> callback : callbacks) {
          try {
            callback.accept(quoteUpdate);
          } catch (Exception e) {
            log.error("Callback error for {}", symbol, e);
          }
        }

        // Publish to Redis pub/sub
        publisherService.publishMarketDataUpdate(quoteUpdate);

        log.debug("Quote updated: {} @ {} (change: {}%)",
            symbol, regularMarketPrice, changePercent);
      }

    } catch (Exception e) {
      log.error("Failed to process quote", e);
    }
  }

  /**
   * Save market data to Redis
   */
  private void saveToRedis(String symbol, MarketDataUpdate quote, MarketDataUpdate trade) {
    try {
      redisTemplate.opsForValue().set(
          REDIS_QUOTE_PREFIX + symbol,
          quote,
          Duration.ofSeconds(cacheTtlSeconds)
      );
      redisTemplate.opsForValue().set(
          REDIS_TRADE_PREFIX + symbol,
          trade,
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
        for (Object obj : saved) {
          subscribedSymbols.add(obj.toString().toUpperCase());
        }
        log.info("Loaded {} subscriptions from Redis: {}",
            subscribedSymbols.size(), subscribedSymbols);

        // Fetch initial quotes
        if (!subscribedSymbols.isEmpty()) {
          fetchQuotes(new ArrayList<>(subscribedSymbols));
        }
      }
    } catch (Exception e) {
      log.warn("Failed to load subscriptions from Redis: {}", e.getMessage());
    }
  }

  /**
   * Get all subscribed symbols
   */
  public Set<String> getSubscribedSymbols() {
    return new HashSet<>(subscribedSymbols);
  }

  /**
   * Get all cached quotes from Redis
   */
  public Map<String, MarketDataUpdate> getAllCachedQuotes() {
    Map<String, MarketDataUpdate> result = new HashMap<>();

    for (String symbol : subscribedSymbols) {
      MarketDataUpdate quote = getLatestQuote(symbol);
      if (quote != null) {
        result.put(symbol, quote);
      }
    }

    return result;
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
}