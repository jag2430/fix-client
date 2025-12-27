# FIX Client Application

A Spring Boot application that provides a REST API interface to send FIX orders to an exchange using QuickFIX/J, with Redis caching, real-time position tracking, and WebSocket-based portfolio blotter.

## Features

- FIX 4.4 protocol support
- REST API for sending orders and cancellations
- **Redis caching** for orders, executions, and positions
- **Redis pub/sub** for real-time updates
- **WebSocket portfolio blotter** for live position monitoring
- **Position tracking** with P&L calculations
- **Market data integration** (Alpaca ready)
- Execution report tracking
- Session status monitoring
- Support for MARKET and LIMIT orders

## Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   REST Client   │     │ WebSocket Client│     │  Alpaca API     │
│   (curl/UI)     │     │ (Blotter)       │     │  (Market Data)  │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌────────────────────────────────────────────────────────────────┐
│                     FIX Client Application                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │ OrderService │  │PositionSvc  │  │ MarketDataService    │  │
│  │              │  │              │  │ (Alpaca/NoOp)        │  │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘  │
│         │                 │                      │              │
│         ▼                 ▼                      ▼              │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              Redis (Cache + Pub/Sub)                     │   │
│  │  • positions:updates  • executions:updates  • orders:*   │   │
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
- (Optional) Alpaca API credentials for market data

## Quick Start

### 1. Start Redis

```bash
# Using Docker Compose (recommended)
docker-compose up -d

# Or start Redis directly
docker run -d --name redis -p 6379:6379 redis:7-alpine
```

### 2. Build the Application

```bash
mvn clean package
```

### 3. Run the Application

```bash
# Without market data
mvn spring-boot:run

# With Alpaca market data
ALPACA_API_KEY=your_key ALPACA_API_SECRET=your_secret \
MARKET_DATA_PROVIDER=alpaca mvn spring-boot:run
```

The client will:
- Connect to the exchange on localhost:9876
- Connect to Redis on localhost:6379
- Start a REST API on port 8081
- Start a WebSocket server on port 8081

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

### Market Data

```bash
# Get Market Data Status
curl http://localhost:8081/api/portfolio/market-data/status

# Get Latest Market Data for Symbol
curl http://localhost:8081/api/portfolio/market-data/AAPL

# Subscribe to Market Data
curl -X POST http://localhost:8081/api/portfolio/market-data/subscribe \
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
  
  // Subscribe to specific channel
  ws.send(JSON.stringify({ 
    action: 'subscribe', 
    channel: 'positions:updates' 
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
| `ORDER_NEW` | New order acknowledged |
| `ORDER_FILLED` | Order fully filled |
| `ORDER_PARTIAL_FILL` | Order partially filled |
| `ORDER_CANCELLED` | Order cancelled |
| `ORDER_REJECTED` | Order rejected |

## Redis Channels

Subscribe to these Redis pub/sub channels for updates:

| Channel | Description |
|---------|-------------|
| `positions:updates` | Position changes (qty, P&L) |
| `executions:updates` | New execution reports |
| `orders:updates` | Order status changes |

### Subscribe via redis-cli

```bash
redis-cli SUBSCRIBE positions:updates executions:updates orders:updates
```

## Configuration

### application.yml

```yaml
server:
  port: 8081

fix:
  config-file: quickfix-client.cfg

spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}

redis:
  channels:
    positions: positions:updates
    executions: executions:updates
    orders: orders:updates

market-data:
  provider: ${MARKET_DATA_PROVIDER:none}  # none, alpaca
  alpaca:
    api-key: ${ALPACA_API_KEY:}
    api-secret: ${ALPACA_API_SECRET:}
    base-url: https://paper-api.alpaca.markets
    data-url: wss://stream.data.alpaca.markets/v2
    feed: iex  # iex (free) or sip (paid)
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `REDIS_HOST` | Redis server host | localhost |
| `REDIS_PORT` | Redis server port | 6379 |
| `REDIS_PASSWORD` | Redis password | (empty) |
| `MARKET_DATA_PROVIDER` | Market data provider | none |
| `ALPACA_API_KEY` | Alpaca API key | (empty) |
| `ALPACA_API_SECRET` | Alpaca API secret | (empty) |

## Project Structure

```
fix-client/
├── docker-compose.yml           # Redis setup
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
│   │   │   │   ├── AlpacaMarketDataService.java
│   │   │   │   ├── ExecutionService.java
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

## Example Workflow

1. Start Redis and exchange simulator
2. Start this client
3. Send a limit buy order:
   ```bash
   curl -X POST http://localhost:8081/api/orders \
     -H "Content-Type: application/json" \
     -d '{"symbol":"AAPL","side":"BUY","orderType":"LIMIT","quantity":100,"price":150.00}'
   ```
4. Check portfolio positions:
   ```bash
   curl http://localhost:8081/api/portfolio/summary
   ```
5. Send a sell order to match:
   ```bash
   curl -X POST http://localhost:8081/api/orders \
     -H "Content-Type: application/json" \
     -d '{"symbol":"AAPL","side":"SELL","orderType":"LIMIT","quantity":50,"price":149.00}'
   ```
6. Watch positions update in real-time via WebSocket

## Monitoring with Redis Commander

Access the Redis Commander UI at http://localhost:8085 to view:
- Cached orders and positions
- Pub/sub activity
- Key expiration

## License

MIT
