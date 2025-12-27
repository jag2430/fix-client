package com.example.client.service;

import com.example.client.model.MarketDataUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Alpaca market data service implementation.
 * Connects to Alpaca's streaming API for real-time market data.
 * 
 * Enable by setting market-data.provider=alpaca in application.yml
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "market-data.provider", havingValue = "alpaca")
public class AlpacaMarketDataService implements MarketDataService {

    @Value("${market-data.alpaca.api-key:}")
    private String apiKey;

    @Value("${market-data.alpaca.api-secret:}")
    private String apiSecret;

    @Value("${market-data.alpaca.data-url:wss://stream.data.alpaca.markets/v2}")
    private String dataUrl;

    @Value("${market-data.alpaca.feed:iex}")
    private String feed;

    private final ObjectMapper objectMapper;
    private final PositionService positionService;
    private final RedisPublisherService publisherService;

    private WebSocket webSocket;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private volatile boolean connected = false;
    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
    private final List<Consumer<MarketDataUpdate>> callbacks = new CopyOnWriteArrayList<>();
    private final Map<String, MarketDataUpdate> latestQuotes = new ConcurrentHashMap<>();
    private final Map<String, MarketDataUpdate> latestTrades = new ConcurrentHashMap<>();

    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();

    public AlpacaMarketDataService(ObjectMapper objectMapper,
                                   PositionService positionService,
                                   RedisPublisherService publisherService) {
        this.objectMapper = objectMapper;
        this.positionService = positionService;
        this.publisherService = publisherService;
    }

    @PostConstruct
    public void init() {
        if (apiKey != null && !apiKey.isEmpty()) {
            log.info("Alpaca market data service configured, connecting...");
            connect();
        } else {
            log.warn("Alpaca API credentials not configured. Market data will not be available.");
        }
    }

    @PreDestroy
    public void cleanup() {
        disconnect();
        reconnectExecutor.shutdown();
    }

    @Override
    public void connect() {
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("Cannot connect: Alpaca API key not configured");
            return;
        }

        String wsUrl = dataUrl + "/" + feed;
        log.info("Connecting to Alpaca WebSocket: {}", wsUrl);

        try {
            CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new AlpacaWebSocketListener());

            webSocket = wsFuture.get(10, TimeUnit.SECONDS);
            log.info("Connected to Alpaca WebSocket");
        } catch (Exception e) {
            log.error("Failed to connect to Alpaca WebSocket", e);
            scheduleReconnect();
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client disconnect");
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void subscribeQuotes(List<String> symbols) {
        if (!connected) {
            log.warn("Cannot subscribe: not connected");
            return;
        }

        try {
            Map<String, Object> message = Map.of(
                "action", "subscribe",
                "quotes", symbols
            );
            webSocket.sendText(objectMapper.writeValueAsString(message), true);
            subscribedSymbols.addAll(symbols);
            log.info("Subscribed to quotes: {}", symbols);
        } catch (Exception e) {
            log.error("Failed to subscribe to quotes", e);
        }
    }

    @Override
    public void subscribeTrades(List<String> symbols) {
        if (!connected) {
            log.warn("Cannot subscribe: not connected");
            return;
        }

        try {
            Map<String, Object> message = Map.of(
                "action", "subscribe",
                "trades", symbols
            );
            webSocket.sendText(objectMapper.writeValueAsString(message), true);
            subscribedSymbols.addAll(symbols);
            log.info("Subscribed to trades: {}", symbols);
        } catch (Exception e) {
            log.error("Failed to subscribe to trades", e);
        }
    }

    @Override
    public void subscribeBars(List<String> symbols) {
        if (!connected) {
            log.warn("Cannot subscribe: not connected");
            return;
        }

        try {
            Map<String, Object> message = Map.of(
                "action", "subscribe",
                "bars", symbols
            );
            webSocket.sendText(objectMapper.writeValueAsString(message), true);
            subscribedSymbols.addAll(symbols);
            log.info("Subscribed to bars: {}", symbols);
        } catch (Exception e) {
            log.error("Failed to subscribe to bars", e);
        }
    }

    @Override
    public void unsubscribe(List<String> symbols) {
        if (!connected) return;

        try {
            Map<String, Object> message = Map.of(
                "action", "unsubscribe",
                "trades", symbols,
                "quotes", symbols,
                "bars", symbols
            );
            webSocket.sendText(objectMapper.writeValueAsString(message), true);
            subscribedSymbols.removeAll(symbols);
            log.info("Unsubscribed from: {}", symbols);
        } catch (Exception e) {
            log.error("Failed to unsubscribe", e);
        }
    }

    @Override
    public MarketDataUpdate getLatestQuote(String symbol) {
        return latestQuotes.get(symbol);
    }

    @Override
    public MarketDataUpdate getLatestTrade(String symbol) {
        return latestTrades.get(symbol);
    }

    @Override
    public void registerCallback(Consumer<MarketDataUpdate> callback) {
        callbacks.add(callback);
    }

    @Override
    public String getProviderName() {
        return "Alpaca";
    }

    private void authenticate() {
        try {
            Map<String, Object> authMessage = Map.of(
                "action", "auth",
                "key", apiKey,
                "secret", apiSecret
            );
            webSocket.sendText(objectMapper.writeValueAsString(authMessage), true);
            log.debug("Sent authentication message");
        } catch (Exception e) {
            log.error("Failed to send authentication", e);
        }
    }

    private void handleMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);

            if (root.isArray()) {
                for (JsonNode node : root) {
                    processMessage(node);
                }
            } else {
                processMessage(root);
            }
        } catch (Exception e) {
            log.error("Failed to parse message: {}", message, e);
        }
    }

    private void processMessage(JsonNode node) {
        String msgType = node.path("T").asText();

        switch (msgType) {
            case "success":
                String msg = node.path("msg").asText();
                if ("connected".equals(msg)) {
                    log.info("WebSocket connected, authenticating...");
                    authenticate();
                } else if ("authenticated".equals(msg)) {
                    log.info("Successfully authenticated with Alpaca");
                    connected = true;
                    // Resubscribe if we have symbols
                    if (!subscribedSymbols.isEmpty()) {
                        subscribeTrades(new ArrayList<>(subscribedSymbols));
                        subscribeQuotes(new ArrayList<>(subscribedSymbols));
                    }
                }
                break;

            case "error":
                log.error("Alpaca error: {} - {}", node.path("code").asInt(), node.path("msg").asText());
                break;

            case "t": // Trade
                handleTrade(node);
                break;

            case "q": // Quote
                handleQuote(node);
                break;

            case "b": // Bar
                handleBar(node);
                break;

            case "subscription":
                log.info("Subscription confirmed: trades={}, quotes={}, bars={}",
                    node.path("trades"), node.path("quotes"), node.path("bars"));
                break;

            default:
                log.debug("Unknown message type: {}", msgType);
        }
    }

    private void handleTrade(JsonNode node) {
        String symbol = node.path("S").asText();
        BigDecimal price = new BigDecimal(node.path("p").asText());
        BigDecimal size = new BigDecimal(node.path("s").asText());

        MarketDataUpdate update = MarketDataUpdate.builder()
            .symbol(symbol)
            .price(price)
            .volume(size)
            .source("alpaca")
            .updateType("TRADE")
            .timestamp(LocalDateTime.now())
            .build();

        latestTrades.put(symbol, update);

        // Update position with latest price
        positionService.updateMarketPrice(symbol, price);

        // Notify callbacks
        for (Consumer<MarketDataUpdate> callback : callbacks) {
            callback.accept(update);
        }

        log.debug("Trade: {} @ {} x {}", symbol, price, size);
    }

    private void handleQuote(JsonNode node) {
        String symbol = node.path("S").asText();
        BigDecimal bidPrice = new BigDecimal(node.path("bp").asText());
        BigDecimal askPrice = new BigDecimal(node.path("ap").asText());
        BigDecimal bidSize = new BigDecimal(node.path("bs").asText());
        BigDecimal askSize = new BigDecimal(node.path("as").asText());

        // Mid price
        BigDecimal midPrice = bidPrice.add(askPrice).divide(BigDecimal.valueOf(2));

        MarketDataUpdate update = MarketDataUpdate.builder()
            .symbol(symbol)
            .price(midPrice)
            .bidPrice(bidPrice)
            .askPrice(askPrice)
            .bidSize(bidSize)
            .askSize(askSize)
            .source("alpaca")
            .updateType("QUOTE")
            .timestamp(LocalDateTime.now())
            .build();

        latestQuotes.put(symbol, update);

        // Notify callbacks
        for (Consumer<MarketDataUpdate> callback : callbacks) {
            callback.accept(update);
        }

        log.debug("Quote: {} bid={} ask={}", symbol, bidPrice, askPrice);
    }

    private void handleBar(JsonNode node) {
        String symbol = node.path("S").asText();

        MarketDataUpdate update = MarketDataUpdate.builder()
            .symbol(symbol)
            .open(new BigDecimal(node.path("o").asText()))
            .high(new BigDecimal(node.path("h").asText()))
            .low(new BigDecimal(node.path("l").asText()))
            .price(new BigDecimal(node.path("c").asText())) // Close as current price
            .volume(new BigDecimal(node.path("v").asText()))
            .vwap(node.has("vw") ? new BigDecimal(node.path("vw").asText()) : null)
            .source("alpaca")
            .updateType("BAR")
            .timestamp(LocalDateTime.now())
            .build();

        // Notify callbacks
        for (Consumer<MarketDataUpdate> callback : callbacks) {
            callback.accept(update);
        }

        log.debug("Bar: {} O={} H={} L={} C={}", symbol, 
            update.getOpen(), update.getHigh(), update.getLow(), update.getPrice());
    }

    private void scheduleReconnect() {
        reconnectExecutor.schedule(() -> {
            if (!connected) {
                log.info("Attempting to reconnect to Alpaca...");
                connect();
            }
        }, 5, TimeUnit.SECONDS);
    }

    private class AlpacaWebSocketListener implements WebSocket.Listener {

        private StringBuilder messageBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("Alpaca WebSocket opened");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            if (last) {
                handleMessage(messageBuffer.toString());
                messageBuffer = new StringBuilder();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("Alpaca WebSocket closed: {} - {}", statusCode, reason);
            connected = false;
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("Alpaca WebSocket error", error);
            connected = false;
            scheduleReconnect();
        }
    }
}
