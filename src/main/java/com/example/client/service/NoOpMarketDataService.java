package com.example.client.service;

import com.example.client.model.MarketDataUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * No-op market data service when no provider is configured.
 * Activated when market-data.provider is 'none' or not set.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "market-data.provider", havingValue = "none", matchIfMissing = true)
public class NoOpMarketDataService implements MarketDataService {

    public NoOpMarketDataService() {
        log.info("No market data provider configured. Real-time prices will not be available.");
        log.info("To enable market data, set market-data.provider=alpaca in application.yml");
    }

    @Override
    public void connect() {
        log.debug("No-op: connect called but no market data provider configured");
    }

    @Override
    public void disconnect() {
        log.debug("No-op: disconnect called");
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public void subscribeQuotes(List<String> symbols) {
        log.debug("No-op: subscribeQuotes called for {}", symbols);
    }

    @Override
    public void subscribeTrades(List<String> symbols) {
        log.debug("No-op: subscribeTrades called for {}", symbols);
    }

    @Override
    public void subscribeBars(List<String> symbols) {
        log.debug("No-op: subscribeBars called for {}", symbols);
    }

    @Override
    public void unsubscribe(List<String> symbols) {
        log.debug("No-op: unsubscribe called for {}", symbols);
    }

    @Override
    public MarketDataUpdate getLatestQuote(String symbol) {
        return null;
    }

    @Override
    public MarketDataUpdate getLatestTrade(String symbol) {
        return null;
    }

    @Override
    public void registerCallback(Consumer<MarketDataUpdate> callback) {
        log.debug("No-op: registerCallback called");
    }

    @Override
    public String getProviderName() {
        return "none";
    }
}
