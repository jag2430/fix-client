# FIX Client Application

A Spring Boot application that provides a REST API interface to send FIX orders to an exchange using QuickFIX/J.

## Features

- FIX 4.4 protocol support
- REST API for sending orders and cancellations
- Execution report tracking
- Session status monitoring
- Support for MARKET and LIMIT orders

## Prerequisites

- Java 17+
- Maven 3.6+
- Running FIX Exchange Simulator (on port 9876)

## Building

```bash
cd fix-client
mvn clean package
```

## Running

Make sure the exchange simulator is running first, then:

```bash
mvn spring-boot:run
```

The client will:
- Connect to the exchange on localhost:9876
- Start a REST API on port 8081

## REST API Endpoints

### Send Order
```bash
# Limit Order
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "AAPL",
    "side": "BUY",
    "orderType": "LIMIT",
    "quantity": 100,
    "price": 150.00
  }'

# Market Order
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "AAPL",
    "side": "SELL",
    "orderType": "MARKET",
    "quantity": 50
  }'
```

### Cancel Order
```bash
curl -X DELETE "http://localhost:8081/api/orders/{clOrdId}?symbol=AAPL&side=BUY"
```

### Get Sent Orders
```bash
curl http://localhost:8081/api/orders
```

### Get Executions
```bash
# All executions
curl http://localhost:8081/api/executions

# Executions for specific order
curl "http://localhost:8081/api/executions?clOrdId=ABC123"

# Recent executions with limit
curl "http://localhost:8081/api/executions?limit=10"
```

### Session Status
```bash
curl http://localhost:8081/api/sessions
```

### Health Check
```bash
curl http://localhost:8081/api/health
```

## Configuration

Edit `src/main/resources/quickfix-client.cfg` to change:
- Exchange host/port
- SenderCompID/TargetCompID
- Heartbeat interval

## Project Structure

```
fix-client/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/example/client/
│   │   │   ├── FixClientApplication.java
│   │   │   ├── config/
│   │   │   │   └── FixConfig.java
│   │   │   ├── controller/
│   │   │   │   └── OrderController.java
│   │   │   ├── fix/
│   │   │   │   └── ClientFixApplication.java
│   │   │   ├── model/
│   │   │   │   ├── CancelRequest.java
│   │   │   │   ├── ExecutionMessage.java
│   │   │   │   ├── OrderRequest.java
│   │   │   │   ├── OrderResponse.java
│   │   │   │   └── SessionStatus.java
│   │   │   └── service/
│   │   │       ├── ExecutionService.java
│   │   │       └── OrderService.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── quickfix-client.cfg
```

## Example Workflow

1. Start the exchange simulator
2. Start this client
3. Send a limit buy order:
   ```bash
   curl -X POST http://localhost:8081/api/orders \
     -H "Content-Type: application/json" \
     -d '{"symbol":"AAPL","side":"BUY","orderType":"LIMIT","quantity":100,"price":150.00}'
   ```
4. Check executions:
   ```bash
   curl http://localhost:8081/api/executions
   ```
5. Send a sell order to match:
   ```bash
   curl -X POST http://localhost:8081/api/orders \
     -H "Content-Type: application/json" \
     -d '{"symbol":"AAPL","side":"SELL","orderType":"LIMIT","quantity":50,"price":149.00}'
   ```

## License

MIT
