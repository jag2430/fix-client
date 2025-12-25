package com.example.client.config;

import com.example.client.fix.ClientFixApplication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import quickfix.*;

import jakarta.annotation.PreDestroy;
import java.io.InputStream;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class FixConfig {
    
    private final ClientFixApplication clientFixApplication;
    
    @Value("${fix.config-file}")
    private String configFile;
    
    private SocketInitiator initiator;
    
    @Bean
    public SessionSettings sessionSettings() throws ConfigError {
        try (InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream(configFile)) {
            if (inputStream == null) {
                throw new ConfigError("Config file not found: " + configFile);
            }
            return new SessionSettings(inputStream);
        } catch (Exception e) {
            throw new ConfigError("Failed to load FIX configuration: " + e.getMessage());
        }
    }
    
    @Bean
    public MessageStoreFactory messageStoreFactory(SessionSettings settings) {
        return new FileStoreFactory(settings);
    }
    
    @Bean
    public LogFactory logFactory(SessionSettings settings) {
        return new FileLogFactory(settings);
    }
    
    @Bean
    public MessageFactory messageFactory() {
        return new DefaultMessageFactory();
    }
    
    @Bean
    public SocketInitiator socketInitiator(
            SessionSettings settings,
            MessageStoreFactory storeFactory,
            LogFactory logFactory,
            MessageFactory messageFactory) throws ConfigError {
        
        initiator = new SocketInitiator(
            clientFixApplication,
            storeFactory,
            settings,
            logFactory,
            messageFactory
        );
        
        return initiator;
    }
    
    @Bean
    public InitiatorStarter initiatorStarter(SocketInitiator initiator) {
        return new InitiatorStarter(initiator);
    }
    
    @PreDestroy
    public void stopInitiator() {
        if (initiator != null && initiator.isLoggedOn()) {
            log.info("Stopping FIX initiator...");
            initiator.stop();
        }
    }
    
    @RequiredArgsConstructor
    public static class InitiatorStarter {
        private final SocketInitiator initiator;
        
        @jakarta.annotation.PostConstruct
        public void start() throws ConfigError {
            log.info("Starting FIX initiator...");
            initiator.start();
            log.info("FIX initiator started, connecting to exchange...");
        }
    }
}
