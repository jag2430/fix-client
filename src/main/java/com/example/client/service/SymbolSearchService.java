package com.example.client.service;

import com.example.client.model.SymbolInfo;
import com.example.client.model.SymbolSearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for symbol search and validation using Finnhub API.
 *
 * Provides:
 * - Symbol search/autocomplete
 * - Symbol validation
 * - Company profile lookup
 * - Quote data for validated symbols
 */
@Slf4j
@Service
public class SymbolSearchService {

  private static final String FINNHUB_URL = "https://finnhub.io/api/v1";
  private static final String REDIS_SYMBOL_INFO_PREFIX = "symbol:info:";
  private static final String REDIS_SEARCH_PREFIX = "symbol:search:";

  @Value("${market-data.finnhub.api-key:}")
  private String apiKey;

  @Value("${symbol-search.cache-ttl-seconds:300}")
  private long cacheTtlSeconds;

  @Value("${symbol-search.max-results:10}")
  private int maxResults;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final RedisTemplate<String, Object> redisTemplate;

  // Local cache for fast repeated lookups
  private final Map<String, SymbolInfo> symbolInfoCache = new ConcurrentHashMap<>();
  private final Map<String, List<SymbolSearchResult>> searchCache = new ConcurrentHashMap<>();

  public SymbolSearchService(ObjectMapper objectMapper,
                             RedisTemplate<String, Object> redisTemplate) {
    this.objectMapper = objectMapper;
    this.redisTemplate = redisTemplate;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  @PostConstruct
  public void init() {
    if (apiKey == null || apiKey.isEmpty()) {
      log.warn("Finnhub API key not configured - symbol search will not work");
    } else {
      log.info("SymbolSearchService initialized with Finnhub API");
    }
  }

  /**
   * Search for symbols matching a query string.
   * Uses Finnhub's symbol search endpoint.
   *
   * @param query Search query (e.g., "AAPL" or "Apple")
   * @return List of matching symbols
   */
  public List<SymbolSearchResult> searchSymbols(String query) {
    if (query == null || query.trim().isEmpty()) {
      return Collections.emptyList();
    }

    String normalizedQuery = query.trim().toUpperCase();

    // Check local cache first
    List<SymbolSearchResult> cached = searchCache.get(normalizedQuery);
    if (cached != null) {
      log.debug("Search cache hit for: {}", normalizedQuery);
      return cached;
    }

    // Check Redis cache
    try {
      String redisKey = REDIS_SEARCH_PREFIX + normalizedQuery;
      Object redisCached = redisTemplate.opsForValue().get(redisKey);
      if (redisCached instanceof List) {
        @SuppressWarnings("unchecked")
        List<SymbolSearchResult> results = (List<SymbolSearchResult>) redisCached;
        searchCache.put(normalizedQuery, results);
        log.debug("Redis search cache hit for: {}", normalizedQuery);
        return results;
      }
    } catch (Exception e) {
      log.warn("Failed to get search results from Redis: {}", e.getMessage());
    }

    // Fetch from Finnhub
    List<SymbolSearchResult> results = fetchSearchResults(normalizedQuery);

    // Cache results
    if (!results.isEmpty()) {
      searchCache.put(normalizedQuery, results);
      try {
        redisTemplate.opsForValue().set(
            REDIS_SEARCH_PREFIX + normalizedQuery,
            results,
            Duration.ofSeconds(cacheTtlSeconds)
        );
      } catch (Exception e) {
        log.warn("Failed to cache search results in Redis: {}", e.getMessage());
      }
    }

    return results;
  }

  /**
   * Validate a symbol and get detailed information.
   *
   * @param symbol Symbol to validate (e.g., "AAPL")
   * @return SymbolInfo with validation status and details
   */
  public SymbolInfo validateSymbol(String symbol) {
    if (symbol == null || symbol.trim().isEmpty()) {
      return SymbolInfo.builder()
          .symbol(symbol)
          .valid(false)
          .validationMessage("Symbol cannot be empty")
          .build();
    }

    String normalizedSymbol = symbol.trim().toUpperCase();

    // Check local cache
    SymbolInfo cached = symbolInfoCache.get(normalizedSymbol);
    if (cached != null && !isCacheExpired(cached)) {
      log.debug("Symbol info cache hit for: {}", normalizedSymbol);
      return cached;
    }

    // Check Redis cache
    try {
      String redisKey = REDIS_SYMBOL_INFO_PREFIX + normalizedSymbol;
      Object redisCached = redisTemplate.opsForValue().get(redisKey);
      if (redisCached instanceof SymbolInfo) {
        SymbolInfo info = (SymbolInfo) redisCached;
        if (!isCacheExpired(info)) {
          symbolInfoCache.put(normalizedSymbol, info);
          log.debug("Redis symbol info cache hit for: {}", normalizedSymbol);
          return info;
        }
      }
    } catch (Exception e) {
      log.warn("Failed to get symbol info from Redis: {}", e.getMessage());
    }

    // Fetch from Finnhub
    SymbolInfo info = fetchSymbolInfo(normalizedSymbol);

    // Cache results
    symbolInfoCache.put(normalizedSymbol, info);
    try {
      redisTemplate.opsForValue().set(
          REDIS_SYMBOL_INFO_PREFIX + normalizedSymbol,
          info,
          Duration.ofSeconds(cacheTtlSeconds)
      );
    } catch (Exception e) {
      log.warn("Failed to cache symbol info in Redis: {}", e.getMessage());
    }

    return info;
  }

  /**
   * Quick validation - just checks if symbol exists without full profile lookup.
   */
  public boolean isValidSymbol(String symbol) {
    SymbolInfo info = validateSymbol(symbol);
    return info != null && info.isValid();
  }

  /**
   * Get company name for a symbol (quick lookup).
   */
  public String getCompanyName(String symbol) {
    SymbolInfo info = validateSymbol(symbol);
    return info != null && info.isValid() ? info.getName() : null;
  }

  /**
   * Clear all caches.
   */
  public void clearCache() {
    symbolInfoCache.clear();
    searchCache.clear();
    log.info("Symbol search caches cleared");
  }

  // =========================================================================
  // Private Methods - Finnhub API Calls
  // =========================================================================

  private List<SymbolSearchResult> fetchSearchResults(String query) {
    if (apiKey == null || apiKey.isEmpty()) {
      log.warn("Cannot search symbols - Finnhub API key not configured");
      return Collections.emptyList();
    }

    try {
      String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
      String url = String.format("%s/search?q=%s&token=%s",
          FINNHUB_URL, encodedQuery, apiKey);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Accept", "application/json")
          .GET()
          .timeout(Duration.ofSeconds(10))
          .build();

      long startTime = System.currentTimeMillis();
      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());
      long elapsed = System.currentTimeMillis() - startTime;

      log.debug("Finnhub search for '{}' took {}ms", query, elapsed);

      if (response.statusCode() == 200) {
        return parseSearchResponse(response.body());
      } else if (response.statusCode() == 429) {
        log.warn("Finnhub rate limit reached during symbol search");
      } else {
        log.warn("Finnhub search API returned {}: {}",
            response.statusCode(), response.body());
      }

    } catch (Exception e) {
      log.error("Failed to search symbols: {}", e.getMessage());
    }

    return Collections.emptyList();
  }

  private List<SymbolSearchResult> parseSearchResponse(String jsonResponse) {
    List<SymbolSearchResult> results = new ArrayList<>();

    try {
      JsonNode root = objectMapper.readTree(jsonResponse);
      JsonNode resultArray = root.path("result");

      if (resultArray.isArray()) {
        int count = 0;
        for (JsonNode item : resultArray) {
          if (count >= maxResults) break;

          // Filter to US stocks only (optional - remove if you want all)
          String type = item.path("type").asText("");

          SymbolSearchResult result = SymbolSearchResult.builder()
              .symbol(item.path("symbol").asText())
              .description(item.path("description").asText())
              .type(type)
              .displaySymbol(item.path("displaySymbol").asText())
              .build();

          // Only include if symbol is not empty
          if (result.getSymbol() != null && !result.getSymbol().isEmpty()) {
            results.add(result);
            count++;
          }
        }
      }

      log.debug("Parsed {} search results", results.size());

    } catch (Exception e) {
      log.error("Failed to parse search response: {}", e.getMessage());
    }

    return results;
  }

  private SymbolInfo fetchSymbolInfo(String symbol) {
    if (apiKey == null || apiKey.isEmpty()) {
      log.warn("Cannot validate symbol - Finnhub API key not configured");
      return SymbolInfo.builder()
          .symbol(symbol)
          .valid(false)
          .validationMessage("API key not configured")
          .build();
    }

    try {
      // Fetch company profile
      SymbolInfo.SymbolInfoBuilder builder = SymbolInfo.builder()
          .symbol(symbol)
          .lastUpdated(LocalDateTime.now());

      // Get company profile
      JsonNode profile = fetchCompanyProfile(symbol);
      if (profile != null && profile.has("name") && !profile.path("name").asText().isEmpty()) {
        builder.name(profile.path("name").asText())
            .exchange(profile.path("exchange").asText())
            .currency(profile.path("currency").asText())
            .country(profile.path("country").asText())
            .industry(profile.path("finnhubIndustry").asText())
            .webUrl(profile.path("weburl").asText())
            .logoUrl(profile.path("logo").asText());

        // Parse market cap
        double marketCapMillions = profile.path("marketCapitalization").asDouble();
        if (marketCapMillions > 0) {
          BigDecimal marketCap = BigDecimal.valueOf(marketCapMillions * 1_000_000);
          builder.marketCap(marketCap)
              .marketCapFormatted(formatMarketCap(marketCap));
        }

        builder.sharesOutstanding(profile.path("shareOutstanding").asLong());
      }

      // Get quote data
      JsonNode quote = fetchQuote(symbol);
      if (quote != null && quote.path("c").asDouble() > 0) {
        builder.currentPrice(getBigDecimal(quote, "c"))
            .previousClose(getBigDecimal(quote, "pc"))
            .change(getBigDecimal(quote, "d"))
            .changePercent(getBigDecimal(quote, "dp"))
            .high(getBigDecimal(quote, "h"))
            .low(getBigDecimal(quote, "l"))
            .open(getBigDecimal(quote, "o"))
            .valid(true);
      } else if (profile != null && profile.has("name")) {
        // Profile exists but no quote - still valid but note no price
        builder.valid(true)
            .validationMessage("Symbol valid but no quote data available");
      } else {
        builder.valid(false)
            .validationMessage("Symbol not found or not tradeable");
      }

      return builder.build();

    } catch (Exception e) {
      log.error("Failed to validate symbol {}: {}", symbol, e.getMessage());
      return SymbolInfo.builder()
          .symbol(symbol)
          .valid(false)
          .validationMessage("Error validating symbol: " + e.getMessage())
          .lastUpdated(LocalDateTime.now())
          .build();
    }
  }

  private JsonNode fetchCompanyProfile(String symbol) {
    try {
      String url = String.format("%s/stock/profile2?symbol=%s&token=%s",
          FINNHUB_URL, symbol, apiKey);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Accept", "application/json")
          .GET()
          .timeout(Duration.ofSeconds(10))
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        return objectMapper.readTree(response.body());
      }

    } catch (Exception e) {
      log.warn("Failed to fetch company profile for {}: {}", symbol, e.getMessage());
    }

    return null;
  }

  private JsonNode fetchQuote(String symbol) {
    try {
      String url = String.format("%s/quote?symbol=%s&token=%s",
          FINNHUB_URL, symbol, apiKey);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Accept", "application/json")
          .GET()
          .timeout(Duration.ofSeconds(10))
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        return objectMapper.readTree(response.body());
      }

    } catch (Exception e) {
      log.warn("Failed to fetch quote for {}: {}", symbol, e.getMessage());
    }

    return null;
  }

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

  private String formatMarketCap(BigDecimal marketCap) {
    if (marketCap == null) {
      return "N/A";
    }

    double cap = marketCap.doubleValue();

    if (cap >= 1_000_000_000_000L) {
      return String.format("$%.1fT", cap / 1_000_000_000_000L);
    } else if (cap >= 1_000_000_000L) {
      return String.format("$%.1fB", cap / 1_000_000_000L);
    } else if (cap >= 1_000_000L) {
      return String.format("$%.1fM", cap / 1_000_000L);
    } else {
      return String.format("$%.0f", cap);
    }
  }

  private boolean isCacheExpired(SymbolInfo info) {
    if (info.getLastUpdated() == null) {
      return true;
    }
    return info.getLastUpdated()
        .plusSeconds(cacheTtlSeconds)
        .isBefore(LocalDateTime.now());
  }
}