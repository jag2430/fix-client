# FIX Client Application

A Spring Boot middleware application that provides a REST API interface to send FIX orders to an exchange using QuickFIX/J, with Redis caching, real-time position tracking, P&L calculations, and market data integration.

> **This is part of a 4-repository FIX Trading System.** See [fix-trading-ui](https://github.com/jag2430/fix-trading-ui) for the main entry point and system overview.

## Overview

The FIX Client serves as the middleware layer between user-facing applications (Trading UI, Portfolio Blotter) and the FIX exchange. It handles protocol translation, order management, position tracking, and real-time data distribution.

## Features

### FIX Protocol Support
- **FIX 4.4** protocol implementation via QuickFIX/J
- **NewOrderSingle (D)**: Submit new orders
- **OrderCancelRequest (F)**: Cancel existing orders
- **OrderCancelReplaceRequest (G)**: Amend orders (modify quantity/price)
- **ExecutionReport (8)**: Process order acknowledgments and fills

### Order Management
- REST API for order submission, amendment, and cancellation
- Order state tracking with status updates
- Execution report processing and storage
- Support for MARKET and LIMIT order types
- **PostgreSQL Persistence**: Orders and executions stored in database via JPA

### Symbol Search & Validation (Finnhub)
- **Symbol Search**: `/api/symbols/search?q=AAPL` - Real-time autocomplete
- **Symbol Validation**: `/api/symbols/{symbol}/validate` - Company profile, current price, validation status
- **Batch Validation**: `POST /api/symbols/validate-batch` - Validate multiple symbols at once
- **Company Name Lookup**: `/api/symbols/{symbol}/company` - Lightweight endpoint for UI

### Position Tracking & P&L
- Real-time position updates from executions
- **Weighted average cost** calculation
- **Unrealized P&L**: (Current Price - Avg Cost) × Quantity
- **Realized P&L**: Calculated when closing positions
- Automatic position creation on first fill

### Market Data Integration (Finnhub)
- **REST API polling** for quote data (price, open, high, low, change)
- **WebSocket streaming** for real-time trade prices
- **Auto-subscription** when positions are opened (`autoSubscribeToMarketData()`)
- **Redis caching** with configurable TTL
- Position P&L updates as prices change

### Redis Integration
- **Caching**: Orders, positions, executions, market data with TTL
- **Pub/Sub**: Real-time updates to connected clients
- **Execution Indexing**: Fast lookup by clOrdId via `EXECUTION_BY_ORDER_KEY_PREFIX`
- Configurable TTL for different data types

### WebSocket Support
- **Portfolio WebSocket**: `/ws/portfolio` for real-time position and P&L updates
- Execution notifications
- Market data streaming

## Architecture

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                           FIX Client Application                              │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐  │
│  │                         REST Controllers                                │  │
│  │  ┌─────────────────┐  ┌───────────────────┐  ┌────────────────────────┐ │  │
│  │  │ OrderController │  │PortfolioController│  │  SymbolController      │ │  │
│  │  │ POST/PUT/DELETE │  │ GET positions     │  │  GET /symbols/search   │ │  │
│  │  │ /api/orders     │  │ /api/portfolio    │  │  GET /symbols/validate │ │  │
│  │  └────────┬────────┘  └────────┬──────────┘  └────────────────────────┘ │  │
│  └───────────┼────────────────────┼────────────────────────────────────────┘  │
│              │                    │                                           │
│              ▼                    ▼                                           │
│  ┌────────────────────────────────────────────────────────────────────────┐   │
│  │                            Services                                    │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌─────────────┐  ┌────────────┐   │   │
│  │  │ OrderService │  │PositionSvc   │  │SymbolSearch │  │ RedisPubl. │   │   │
│  │  │              │  │  P&L Calc    │  │   Service   │  │  Pub/Sub   │   │   │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬──────┘  └─────┬──────┘   │   │
│  │         │                 │                 │               │          │   │
│  │         │                 │                 │               ▼          │   │
│  │         │                 │                 │         ┌────────────┐   │   │
│  │         │                 │                 │         │   Redis    │   │   │
│  │         │                 │                 │         │Cache+Pub/Sub   │   │
│  │         │                 │                 │         └────────────┘   │   │
│  │         │                 │                 │               │          │   │
│  │         │                 │                 │               ▼          │   │
│  │         │                 │                 │         ┌────────────┐   │   │
│  │         │                 │                 │         │ PostgreSQL │   │   │
│  │         │                 │                 │         │   (JPA)    │   │   │
│  │         │                 │                 │         └────────────┘   │   │
│  └─────────┼─────────────────┼─────────────────┼──────────────────────────┘   │
│            │                 │                 │                              │
│            ▼                 │                 │                              │
│  ┌────────────────────────┐  │                 │                              │
│  │  ClientFixApplication  │<─┘                 │                              │
│  │  (QuickFIX/J)          │  Execution Reports │                              │
│  │                        │                    │                              │
│  │  - fromApp(): Handle   │                    │                              │
│  │    incoming messages   │                    │                              │
│  │  - toApp(): Intercept  │                    │                              │
│  │    outgoing messages   │                    │                              │
│  └───────────┬────────────┘                    │                              │
│              │                                 │                              │
│              │ FIX 4.4 Protocol                │                              │
│              ▼                                 ▼                              │
│  ┌────────────────────────┐      ┌────────────────────────┐                   │
│  │   SocketInitiator      │      │  FinnhubMarketDataSvc  │                   │
│  │   (FIX Connection)     │      │  REST + WebSocket      │                   │
│  └───────────┬────────────┘      └───────────┬────────────┘                   │
└──────────────┼───────────────────────────────┼────────────────────────────────┘
               │                               │
               ▼                               ▼
      ┌─────────────────┐            ┌─────────────────┐
      │  FIX Exchange   │            │    Finnhub      │
      │  (Port 9876)    │            │  (Market Data)  │
      └─────────────────┘            └─────────────────┘
```

## FIX Protocol Details

### Session Configuration

```properties
# quickfix-client.cfg
[DEFAULT]
ConnectionType=initiator
HeartBtInt=30
ReconnectInterval=5
FileStorePath=target/data/fix
FileLogPath=target/log/fix
StartTime=00:00:00
EndTime=00:00:00
UseDataDictionary=Y
DataDictionary=FIX44.xml

[SESSION]
BeginString=FIX.4.4
SenderCompID=BANZAI
TargetCompID=EXEC
SocketConnectHost=localhost
SocketConnectPort=9876
```

### Outgoing Messages (Client → Exchange)

| Message | FIX Type | Purpose | Key Fields |
|---------|----------|---------|------------|
| NewOrderSingle | D | Submit order | ClOrdID, Symbol, Side, OrderQty, OrdType, Price |
| OrderCancelRequest | F | Cancel order | OrigClOrdID, ClOrdID, Symbol, Side |
| OrderCancelReplaceRequest | G | Amend order | OrigClOrdID, ClOrdID, OrderQty, Price |

### Incoming Messages (Exchange → Client)

| Message | FIX Type | Purpose | Key Fields |
|---------|----------|---------|------------|
| ExecutionReport | 8 | Order status/fills | ExecID, ExecType, OrdStatus, LastQty, LastPx, CumQty, LeavesQty |

### Execution Types Handled

| ExecType | Value | Description | Action |
|----------|-------|-------------|--------|
| NEW | 0 | Order accepted | Update order status |
| PARTIAL_FILL | 1 | Partial execution | Update position, publish fill |
| FILL | 2 | Full execution | Update position, publish fill |
| CANCELLED | 4 | Order cancelled | Update order status |
| REPLACED | 5 | Order amended | Update order details |
| REJECTED | 8 | Order rejected | Update order status |

## Redis Integration

### Caching Strategy

| Key Pattern | Data | TTL | Purpose |
|-------------|------|-----|---------|
| `order:{clOrdId}` | Order JSON | 24 hours | Order state cache |
| `position:{symbol}` | Position JSON | 1 hour | Position cache |
| `marketdata:quote:{symbol}` | Quote JSON | 5 minutes | Price cache |
| `marketdata:trade:{symbol}` | Trade JSON | 5 minutes | Last trade cache |
| `orders:all` | Set of ClOrdIds | None | Track all orders |
| `orders:open` | Set of ClOrdIds | None | Track open orders |
| `executions:list` | List of executions | 24 hours | Execution history |
| `executions:order:{clOrdId}` | List of execIds | 24 hours | Index by order |
| `positions:symbols` | Set of symbols | None | Active positions |
| `marketdata:subscriptions` | Set of symbols | None | Market data subscriptions |
| `symbol:info:{symbol}` | Symbol info JSON | 1 hour | Symbol search cache |

### Pub/Sub Channels

| Channel | Publisher | Content | Subscribers |
|---------|-----------|---------|-------------|
| `positions:updates` | PositionService | Position changes (qty, P&L) | Blotter |
| `executions:updates` | ExecutionService | New execution reports | Blotter, UI |
| `orders:updates` | OrderService | Order status changes | Blotter, UI |
| `marketdata:updates` | FinnhubService | Price updates | Blotter |

### Message Format

```json
{
  "type": "POSITION_UPDATE",
  "timestamp": "2024-01-15T14:30:25.123Z",
  "data": {
    "symbol": "AAPL",
    "quantity": 100,
    "avgCost": 150.25,
    "currentPrice": 155.00,
    "marketValue": 15500.00,
    "unrealizedPnl": 475.00,
    "realizedPnl": 0.00
  }
}
```

## Position & P&L Calculation

### Position Updates on Fill

```java
// When a BUY fill is received:
newQuantity = currentQuantity + fillQuantity;
newTotalCost = currentTotalCost + (fillQuantity * fillPrice);
newAvgCost = newTotalCost / newQuantity;

// When a SELL fill is received (closing position):
realizedPnl += (fillPrice - avgCost) * fillQuantity;
newQuantity = currentQuantity - fillQuantity;
```

### P&L Formulas

| Metric | Formula |
|--------|---------|
| Market Value | `quantity × currentPrice` |
| Total Cost | `quantity × avgCost` |
| Unrealized P&L | `(currentPrice - avgCost) × quantity` |
| Unrealized P&L % | `((currentPrice - avgCost) / avgCost) × 100` |
| Realized P&L | Sum of closed trade profits/losses |

## Market Data (Finnhub)

### Features
- **REST Polling**: Fetches quotes every 5 seconds for subscribed symbols
- **WebSocket**: Real-time trade stream for instant price updates
- **Auto-Subscribe**: When a position is opened, automatically subscribes to market data
- **Price Cache**: Redis caching to reduce API calls
- **Rate Limiting**: Handles 429 responses and 60 calls/min limit

### Configuration

```yaml
market-data:
  provider: finnhub
  finnhub:
    api-key: ${FINNHUB_API_KEY:}
    use-websocket: true
    refresh-interval-ms: 5000
    cache-ttl-seconds: 300
```

### Free Tier Limits
- **REST API**: 60 calls/minute
- **WebSocket**: Unlimited real-time trades for US stocks
- **No credit card required**

## Prerequisites

- Java 17+
- Maven 3.6+
- Redis 6+ (or use Docker)
- PostgreSQL 14+ (or use Docker)
- Running FIX Exchange Simulator (on port 9876)
- **Finnhub API Key** (free): https://finnhub.io/register

## Quick Start

### 1. Sign Up For Free Finnhub API Key

1. Go to https://finnhub.io/register
2. Sign up with email (no credit card required)
3. Copy your API key from the dashboard

### 2. Start Infrastructure (Redis + PostgreSQL)

```bash
# Using Docker Compose (recommended)
docker-compose up -d

# This starts:
# - Redis on port 6379
# - PostgreSQL on port 5432
# - Redis Commander on port 8085
```

### 3. Build the Application

```bash
mvn clean package
```

### 4. Run the Application

```bash
# With Finnhub market data (recommended)
FINNHUB_API_KEY=your_api_key_here mvn spring-boot:run

# Without market data
MARKET_DATA_PROVIDER=none mvn spring-boot:run
```

The client will:
- Connect to the exchange on localhost:9876
- Connect to Redis on localhost:6379
- Connect to PostgreSQL on localhost:5432
- Start a REST API on port 8081
- Connect to Finnhub for market data

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

### Symbol Search & Validation

```bash
# Search symbols (autocomplete)
curl "http://localhost:8081/api/symbols/search?q=AAPL"

# Response:
[
  {"symbol": "AAPL", "description": "Apple Inc", "type": "Common Stock"},
  {"symbol": "AAPL.SW", "description": "Apple Inc", "type": "Common Stock"}
]

# Validate symbol (get full details)
curl http://localhost:8081/api/symbols/AAPL/validate

# Response:
{
  "valid": true,
  "symbol": "AAPL",
  "companyName": "Apple Inc",
  "currentPrice": 178.50,
  "change": 2.35,
  "changePercent": 1.33,
  "marketCap": 2800000000000
}

# Batch validate symbols
curl -X POST http://localhost:8081/api/symbols/validate-batch \
  -H "Content-Type: application/json" \
  -d '{"symbols": ["AAPL", "MSFT", "INVALID"]}'

# Get company name only
curl http://localhost:8081/api/symbols/AAPL/company
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

# Subscribe to Market Data
curl -X POST http://localhost:8081/api/portfolio/market-data/subscribe \
  -H "Content-Type: application/json" \
  -d '{"symbols": ["AAPL", "MSFT", "GOOGL"]}'

# Get Current Subscriptions
curl http://localhost:8081/api/portfolio/market-data/subscriptions

# Get Latest Market Data for Symbol
curl http://localhost:8081/api/portfolio/market-data/AAPL

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

Connect to receive real-time updates:

```javascript
const ws = new WebSocket('ws://localhost:8081/ws/portfolio');

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  
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
  }
};
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `FINNHUB_API_KEY` | Finnhub API key | (required for market data) |
| `FINNHUB_USE_WEBSOCKET` | Enable WebSocket streaming | true |
| `FINNHUB_REFRESH_INTERVAL` | REST polling interval (ms) | 5000 |
| `FINNHUB_CACHE_TTL` | Redis cache TTL (seconds) | 300 |
| `REDIS_HOST` | Redis server host | localhost |
| `REDIS_PORT` | Redis server port | 6379 |
| `REDIS_PASSWORD` | Redis password | (empty) |
| `MARKET_DATA_PROVIDER` | Provider (finnhub/none) | finnhub |
| `POSTGRES_HOST` | PostgreSQL host | localhost |
| `POSTGRES_PORT` | PostgreSQL port | 5432 |
| `POSTGRES_DB` | Database name | fixclient |
| `POSTGRES_USER` | Database user | fixclient |
| `POSTGRES_PASSWORD` | Database password | fixclient |

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
│   │   │   │   ├── PortfolioController.java
│   │   │   │   └── SymbolController.java
│   │   │   ├── fix/
│   │   │   │   └── ClientFixApplication.java
│   │   │   ├── model/
│   │   │   │   ├── ExecutionMessage.java
│   │   │   │   ├── OrderRequest.java
│   │   │   │   ├── OrderResponse.java
│   │   │   │   └── Position.java
│   │   │   ├── repository/
│   │   │   │   ├── OrderRepository.java
│   │   │   │   └── ExecutionRepository.java
│   │   │   ├── service/
│   │   │   │   ├── ExecutionService.java
│   │   │   │   ├── FinnhubMarketDataService.java
│   │   │   │   ├── OrderService.java
│   │   │   │   ├── PositionService.java
│   │   │   │   ├── SymbolSearchService.java
│   │   │   │   └── RedisPublisherService.java
│   │   │   └── websocket/
│   │   │       └── PortfolioWebSocketHandler.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── quickfix-client.cfg
```

## Monitoring

### Redis Commander
Access http://localhost:8085 to view:
- Cached orders and positions
- Market data quotes
- Symbol search cache
- Pub/sub activity

### Subscribe to Redis Channels
```bash
redis-cli SUBSCRIBE positions:updates executions:updates orders:updates marketdata:updates
```

## Troubleshooting

### "FIX session not connected"
- Ensure Exchange Simulator is running on port 9876
- Check SenderCompID/TargetCompID match

### "Finnhub API key not configured"
- Set `FINNHUB_API_KEY` environment variable
- Or configure in `application.yml`

### "Rate limit reached" (429 errors)
- Enable WebSocket mode (default)
- Increase refresh interval
- Reduce subscribed symbols
- The service handles 429 responses gracefully

### Positions not updating
- Check Redis is running: `redis-cli ping`
- Verify executions are being received
- Check FIX Client logs

## Related Repositories

| Repository                                                                  | Description |
|-----------------------------------------------------------------------------|-------------|
| [fix-trading-ui](https://github.com/jag2430/fix-trading-ui)                 | Order entry UI (main entry point) |
| [fix-exchange-simulator](https://github.com/jag2430/fix-exchange-simulator) | Matching engine |
| [portfolio-blotter](https://github.com/jag2430/portfolio-blotter)           | P&L monitoring dashboard |