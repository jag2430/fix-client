package com.example.client.controller;

import com.example.client.model.SymbolInfo;
import com.example.client.model.SymbolSearchResult;
import com.example.client.service.SymbolSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for symbol search and validation.
 *
 * Endpoints:
 * - GET /api/symbols/search?q={query} - Search for symbols
 * - GET /api/symbols/{symbol}/validate - Validate a symbol and get details
 * - GET /api/symbols/{symbol}/info - Get symbol information
 * - POST /api/symbols/validate-batch - Validate multiple symbols
 */
@Slf4j
@RestController
@RequestMapping("/api/symbols")
@RequiredArgsConstructor
public class SymbolController {

  private final SymbolSearchService symbolSearchService;

  /**
   * Search for symbols matching a query string.
   *
   * @param q Search query (e.g., "AAPL" or "Apple")
   * @return List of matching symbols with company names
   */
  @GetMapping("/search")
  public ResponseEntity<Map<String, Object>> searchSymbols(
      @RequestParam("q") String query) {

    if (query == null || query.trim().length() < 1) {
      return ResponseEntity.badRequest().body(Map.of(
          "error", "Query must be at least 1 character",
          "results", List.of()
      ));
    }

    log.debug("Symbol search request: '{}'", query);

    long startTime = System.currentTimeMillis();
    List<SymbolSearchResult> results = symbolSearchService.searchSymbols(query.trim());
    long elapsed = System.currentTimeMillis() - startTime;

    return ResponseEntity.ok(Map.of(
        "query", query.trim().toUpperCase(),
        "count", results.size(),
        "results", results,
        "elapsedMs", elapsed
    ));
  }

  /**
   * Validate a symbol and get detailed information.
   *
   * @param symbol Symbol to validate (e.g., "AAPL")
   * @return Symbol information with validation status
   */
  @GetMapping("/{symbol}/validate")
  public ResponseEntity<SymbolInfo> validateSymbol(@PathVariable String symbol) {
    log.debug("Symbol validation request: '{}'", symbol);

    SymbolInfo info = symbolSearchService.validateSymbol(symbol);

    if (info.isValid()) {
      return ResponseEntity.ok(info);
    } else {
      // Return 200 with valid=false rather than 404
      // This allows the UI to show the validation message
      return ResponseEntity.ok(info);
    }
  }

  /**
   * Get symbol information (alias for validate).
   */
  @GetMapping("/{symbol}/info")
  public ResponseEntity<SymbolInfo> getSymbolInfo(@PathVariable String symbol) {
    return validateSymbol(symbol);
  }

  /**
   * Quick validation check - returns just valid/invalid status.
   */
  @GetMapping("/{symbol}/check")
  public ResponseEntity<Map<String, Object>> checkSymbol(@PathVariable String symbol) {
    log.debug("Symbol check request: '{}'", symbol);

    SymbolInfo info = symbolSearchService.validateSymbol(symbol);

    return ResponseEntity.ok(Map.of(
        "symbol", symbol.toUpperCase(),
        "valid", info.isValid(),
        "name", info.getName() != null ? info.getName() : "",
        "message", info.getValidationMessage() != null ? info.getValidationMessage() : ""
    ));
  }

  /**
   * Validate multiple symbols at once.
   */
  @PostMapping("/validate-batch")
  public ResponseEntity<Map<String, Object>> validateBatch(
      @RequestBody Map<String, List<String>> request) {

    List<String> symbols = request.get("symbols");

    if (symbols == null || symbols.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of(
          "error", "Missing 'symbols' array in request body"
      ));
    }

    log.debug("Batch validation request for {} symbols", symbols.size());

    Map<String, SymbolInfo> results = new java.util.LinkedHashMap<>();
    int validCount = 0;

    for (String symbol : symbols) {
      SymbolInfo info = symbolSearchService.validateSymbol(symbol);
      results.put(symbol.toUpperCase(), info);
      if (info.isValid()) {
        validCount++;
      }
    }

    return ResponseEntity.ok(Map.of(
        "total", symbols.size(),
        "valid", validCount,
        "invalid", symbols.size() - validCount,
        "results", results
    ));
  }

  /**
   * Get company name for a symbol (lightweight endpoint for UI).
   */
  @GetMapping("/{symbol}/name")
  public ResponseEntity<Map<String, String>> getCompanyName(@PathVariable String symbol) {
    String name = symbolSearchService.getCompanyName(symbol);

    if (name != null) {
      return ResponseEntity.ok(Map.of(
          "symbol", symbol.toUpperCase(),
          "name", name
      ));
    } else {
      return ResponseEntity.ok(Map.of(
          "symbol", symbol.toUpperCase(),
          "name", "",
          "error", "Symbol not found"
      ));
    }
  }

  /**
   * Clear symbol caches (for admin/testing).
   */
  @PostMapping("/cache/clear")
  public ResponseEntity<Map<String, String>> clearCache() {
    symbolSearchService.clearCache();
    return ResponseEntity.ok(Map.of(
        "status", "Symbol search cache cleared"
    ));
  }
}