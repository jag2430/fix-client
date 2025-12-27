package com.example.client.service;

import com.example.client.model.MarketDataUpdate;

import java.util.List;
import java.util.function.Consumer;

/**
 * Interface for market data providers.
 * Implementations can include Alpaca, Polygon, or other data sources.
 */
public interface MarketDataService {

    /**
     * Connect to the market data provider
     */
    void connect();

    /**
     * Disconnect from the market data provider
     */
    void disconnect();

    /**
     * Check if connected to the market data provider
     */
    boolean isConnected();

    /**
     * Subscribe to real-time quotes for symbols
     */
    void subscribeQuotes(List<String> symbols);

    /**
     * Subscribe to real-time trades for symbols
     */
    void subscribeTrades(List<String> symbols);

    /**
     * Subscribe to real-time bars for symbols
     */
    void subscribeBars(List<String> symbols);

    /**
     * Unsubscribe from symbols
     */
    void unsubscribe(List<String> symbols);

    /**
     * Get the latest quote for a symbol
     */
    MarketDataUpdate getLatestQuote(String symbol);

    /**
     * Get the latest trade for a symbol
     */
    MarketDataUpdate getLatestTrade(String symbol);

    /**
     * Register a callback for market data updates
     */
    void registerCallback(Consumer<MarketDataUpdate> callback);

    /**
     * Get the provider name
     */
    String getProviderName();
}
