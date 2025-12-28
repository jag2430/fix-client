# FIX Client Application

A Spring Boot application that provides a REST API interface to send FIX orders to an exchange using QuickFIX/J, with Redis caching, real-time position tracking, and WebSocket-based portfolio blotter.

## Features

- FIX 4.4 protocol support
- REST API for sending orders and cancellations
- **Redis caching** for orders, executions, positions, and market data
- **Redis pub/sub** for real-time updates
- **WebSocket portfolio blotter** for live position monitoring
- **Position tracking** with P&L calculations
- **Finnhub market data integration** (free, requires API key sign-up)
- Execution report tracking
- Session status monitoring
- Support for MARKET and LIMIT orders

## Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   REST Client   │     │ WebSocket Client│     │    Finnhub      │
│   (curl/UI)     │     │ (Blotter)       │     │ (Free API Key)  │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌────────────────────────────────────────────────────────────────┐
│                     FIX Client Application                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │ OrderService │  │PositionSvc   │  │ FinnhubMarketDataSvc │  │
│  │              │  │              │  │ (REST + WebSocket)   │  │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘  │
│         │                 │                     │              │
│         ▼                 ▼                     ▼              │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              Redis (Cache + Pub/Sub)                    │   │
│  │  • positions:updates  • executions:updates              │   │
│  │  • orders:updates     • marketdata:updates              │   │
│  │  • marketdata:quote:* • marketdata:subscriptions        │   │
│  └─────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────┐
│  FIX Exchange   │
│  (Port 9876)    │
└─────────────────┘
```

## Prerequisites

- Java 17+
- Maven 3.6+
- Redis 6+ (or use Docker)
- Running FIX Exchange Simulator (on port 9876)
- **Finnhub API Key** (free): https://finnhub.io/register

## Quick Start

### 1. Get Your Free Finnhub API Key

1. Go to https://finnhub.io/register
2. Sign up with email (no credit card required)
3. Copy your API key from the dashboard

### 2. Start Redis

```bash
# Using Docker Compose (recommended)
docker-compose up -d

# Or start Redis directly
docker run -d --name redis -p 6379:6379 redis:7-alpine
```

### 3. Build the Application

```bash
mvn clean package
```

### 4. Run the Application

```bash
# With Finnhub market data (recommended)
FINNHUB_API_KEY=your_api_key_here mvn spring-boot:run

# Or set in application.yml
# market-data.finnhub.api-key: your_api_key_here

# Without market data
MARKET_DATA_PROVIDER=none mvn spring-boot:run
```

The client will:
- Connect to the exchange on localhost:9876
- Connect to Redis on localhost:6379
- Start a REST API on port 8081
- Start a WebSocket server on port 8081
- Connect to Finnhub WebSocket for real-time trades

## Finnhub Market Data

### Free Tier Limits
- **REST API**: 60 calls/minute
- **WebSocket**: Unlimited real-time trades for US stocks, forex, crypto
- **No credit card required**

### Features
- Real-time trade streaming via WebSocket
- Quote data via REST API (current price, open, high, low, previous close)
- Automatic reconnection on disconnect
- Redis caching for persistence
- Position P&L updates in real-time

### Configuration Options

```yaml
market-data:
  provider: finnhub
  finnhub:
    api-key: ${FINNHUB_API_KEY:}
    use-websocket: true          # Enable WebSocket streaming
    refresh-interval-ms: 5000    # REST polling interval (fallback)
    cache-ttl-seconds: 300       # Redis cache TTL
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `FINNHUB_API_KEY` | Your Finnhub API key | (required) |
| `FINNHUB_USE_WEBSOCKET` | Enable WebSocket streaming | true |
| `FINNHUB_REFRESH_INTERVAL` | REST API polling interval (ms) | 5000 |
| `FINNHUB_CACHE_TTL` | Redis cache TTL (seconds) | 300 |
| `REDIS_HOST` | Redis server host | localhost |
| `REDIS_PORT` | Redis server port | 6379 |
| `REDIS_PASSWORD` | Redis password | (empty) |
| `MARKET_DATA_PROVIDER` | Market data provider (finnhub/none) | finnhub |

## REST API Endpoints

### Orders

```bash
# Send Limit Order
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "AAPL",
    "side": "BUY",
    "orderType": "LIMIT",
    "quantity": 100,
    "price": 150.00
  }'

# Send Market Order
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "AAPL",
    "side": "SELL",
    "orderType": "MARKET",
    "quantity": 50
  }'

# Cancel Order
curl -X DELETE "http://localhost:8081/api/orders/{clOrdId}?symbol=AAPL&side=BUY"

# Amend Order
curl -X PUT http://localhost:8081/api/orders/{clOrdId} \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "AAPL",
    "side": "BUY",
    "newQuantity": 150,
    "newPrice": 148.00
  }'

# Get All Orders
curl http://localhost:8081/api/orders

# Get Open Orders Only
curl "http://localhost:8081/api/orders?openOnly=true"

# Get Specific Order
curl http://localhost:8081/api/orders/{clOrdId}
```

### Executions

```bash
# All executions
curl http://localhost:8081/api/executions

# Executions for specific order
curl "http://localhost:8081/api/executions?clOrdId=ABC123"

# Recent executions with limit
curl "http://localhost:8081/api/executions?limit=10"

# Execution count
curl http://localhost:8081/api/executions/count
```

### Portfolio & Positions

```bash
# Get Portfolio Summary
curl http://localhost:8081/api/portfolio/summary

# Get All Positions
curl http://localhost:8081/api/portfolio/positions

# Get Open Positions Only
curl "http://localhost:8081/api/portfolio/positions?openOnly=true"

# Get Position for Symbol
curl http://localhost:8081/api/portfolio/positions/AAPL

# Manually Update Price (for testing)
curl -X POST http://localhost:8081/api/portfolio/positions/AAPL/price \
  -H "Content-Type: application/json" \
  -d '{"price": 155.00}'

# Clear All Positions
curl -X DELETE http://localhost:8081/api/portfolio/positions
```

### Market Data (Finnhub)

```bash
# Get Market Data Status
curl http://localhost:8081/api/portfolio/market-data/status

# Get All Market Data (quotes and subscriptions)
curl http://localhost:8081/api/portfolio/market-data

# Get Latest Market Data for Symbol
curl http://localhost:8081/api/portfolio/market-data/AAPL

# Subscribe to Market Data (WebSocket + REST)
curl -X POST http://localhost:8081/api/portfolio/market-data/subscribe \
  -H "Content-Type: application/json" \
  -d '{"symbols": ["AAPL", "MSFT", "GOOGL", "AMZN", "TSLA"]}'

# Get Current Subscriptions
curl http://localhost:8081/api/portfolio/market-data/subscriptions

# Batch Quote Request
curl -X POST http://localhost:8081/api/portfolio/market-data/quotes \
  -H "Content-Type: application/json" \
  -d '{"symbols": ["AAPL", "MSFT", "GOOGL"]}'

# Unsubscribe from Market Data
curl -X POST http://localhost:8081/api/portfolio/market-data/unsubscribe \
  -H "Content-Type: application/json" \
  -d '{"symbols": ["AAPL"]}'
```

### Session & Health

```bash
# Session Status
curl http://localhost:8081/api/sessions

# Health Check
curl http://localhost:8081/api/health
```

## WebSocket Portfolio Blotter

Connect to the WebSocket endpoint to receive real-time updates:

```javascript
const ws = new WebSocket('ws://localhost:8081/ws/portfolio');

ws.onopen = () => {
  console.log('Connected to portfolio blotter');
  
  // Request current portfolio snapshot
  ws.send(JSON.stringify({ action: 'getPortfolio' }));
  
  // Subscribe to market data channel
  ws.send(JSON.stringify({ 
    action: 'subscribe', 
    channel: 'marketdata:updates' 
  }));
};

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log('Received:', data);
  
  switch (data.type) {
    case 'PORTFOLIO_SNAPSHOT':
      updatePortfolioUI(data.data);
      break;
    case 'POSITION_UPDATE':
      updatePositionRow(data.data);
      break;
    case 'EXECUTION':
      addExecutionToBlotter(data.data);
      break;
    case 'MARKET_DATA':
      updateMarketPrice(data.data);
      break;
    case 'ORDER_NEW':
    case 'ORDER_FILLED':
    case 'ORDER_CANCELLED':
      updateOrderStatus(data.data);
      break;
  }
};

// Keep connection alive
setInterval(() => {
  ws.send(JSON.stringify({ action: 'ping' }));
}, 30000);
```

### WebSocket Message Types

| Type | Description |
|------|-------------|
| `PORTFOLIO_SNAPSHOT` | Complete portfolio state on connect |
| `POSITION_UPDATE` | Real-time position changes |
| `EXECUTION` | New execution reports |
| `MARKET_DATA` | Real-time price updates from Finnhub |
| `ORDER_NEW` | New order acknowledged |
| `ORDER_FILLED` | Order fully filled |
| `ORDER_PARTIAL_FILL` | Order partially filled |
| `ORDER_CANCELLED` | Order cancelled |
| `ORDER_REJECTED` | Order rejected |

## Redis Data Structure

### Cached Keys

| Key Pattern | Description | TTL |
|-------------|-------------|-----|
| `marketdata:quote:{symbol}` | Latest quote for symbol | 5 min |
| `marketdata:trade:{symbol}` | Latest trade for symbol | 5 min |
| `marketdata:subscriptions` | Set of subscribed symbols | None |
| `position:{symbol}` | Position data | 1 hour |
| `order:{clOrdId}` | Order data | 24 hours |
| `executions:list` | List of all executions | 24 hours |

### Pub/Sub Channels

| Channel | Description |
|---------|-------------|
| `positions:updates` | Position changes (qty, P&L) |
| `executions:updates` | New execution reports |
| `orders:updates` | Order status changes |
| `marketdata:updates` | Real-time market data updates |

### Subscribe via redis-cli

```bash
redis-cli SUBSCRIBE positions:updates executions:updates orders:updates marketdata:updates
```

## Example Workflow

1. Start Redis and exchange simulator
2. Set your Finnhub API key and start the client:
   ```bash
   FINNHUB_API_KEY=your_key mvn spring-boot:run
   ```
3. Subscribe to market data:
   ```bash
   curl -X POST http://localhost:8081/api/portfolio/market-data/subscribe \
     -H "Content-Type: application/json" \
     -d '{"symbols": ["AAPL", "MSFT", "GOOGL"]}'
   ```
4. Send a limit buy order:
   ```bash
   curl -X POST http://localhost:8081/api/orders \
     -H "Content-Type: application/json" \
     -d '{"symbol":"AAPL","side":"BUY","orderType":"LIMIT","quantity":100,"price":150.00}'
   ```
5. Check portfolio positions (with live prices from Finnhub):
   ```bash
   curl http://localhost:8081/api/portfolio/summary
   ```
6. Watch positions update in real-time via WebSocket as market prices change

## Finnhub Quote Response Example

```json
{
  "symbol": "AAPL",
  "provider": "Finnhub",
  "quote": {
    "symbol": "AAPL",
    "price": 178.5500,
    "open": 177.2500,
    "high": 179.1200,
    "low": 176.8900,
    "previousClose": 177.0100,
    "change": 1.5400,
    "changePercent": 0.8700,
    "source": "finnhub",
    "updateType": "QUOTE",
    "timestamp": "2024-01-15T14:30:25"
  }
}
```

## Project Structure

```
fix-client/
├── docker-compose.yml           # Redis + PostgreSQL setup
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/example/client/
│   │   │   ├── FixClientApplication.java
│   │   │   ├── config/
│   │   │   │   ├── FixConfig.java
│   │   │   │   ├── RedisConfig.java
│   │   │   │   └── WebSocketConfig.java
│   │   │   ├── controller/
│   │   │   │   ├── OrderController.java
│   │   │   │   └── PortfolioController.java
│   │   │   ├── fix/
│   │   │   │   └── ClientFixApplication.java
│   │   │   ├── model/
│   │   │   │   ├── AmendRequest.java
│   │   │   │   ├── CancelRequest.java
│   │   │   │   ├── ExecutionMessage.java
│   │   │   │   ├── MarketDataUpdate.java
│   │   │   │   ├── OrderRequest.java
│   │   │   │   ├── OrderResponse.java
│   │   │   │   ├── Position.java
│   │   │   │   ├── PortfolioSummary.java
│   │   │   │   └── SessionStatus.java
│   │   │   ├── service/
│   │   │   │   ├── ExecutionService.java
│   │   │   │   ├── FinnhubMarketDataService.java
│   │   │   │   ├── MarketDataService.java
│   │   │   │   ├── NoOpMarketDataService.java
│   │   │   │   ├── OrderService.java
│   │   │   │   ├── PositionService.java
│   │   │   │   └── RedisPublisherService.java
│   │   │   └── websocket/
│   │   │       └── PortfolioWebSocketHandler.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── quickfix-client.cfg
```

## Monitoring with Redis Commander

Access the Redis Commander UI at http://localhost:8085 to view:
- Cached orders and positions
- Market data quotes (`marketdata:quote:*`)
- Subscribed symbols (`marketdata:subscriptions`)
- Pub/sub activity
- Key expiration

## Troubleshooting

### "Finnhub API key not configured"
Make sure you've set the `FINNHUB_API_KEY` environment variable or configured it in `application.yml`.

### "Rate limit reached"
The free tier allows 60 REST API calls per minute. If you're hitting rate limits:
- Enable WebSocket mode (default): `FINNHUB_USE_WEBSOCKET=true`
- Increase refresh interval: `FINNHUB_REFRESH_INTERVAL=10000`
- Reduce number of subscribed symbols

### WebSocket not connecting
Check that your API key is valid and not expired. The WebSocket URL requires a valid token.

## License

MIT