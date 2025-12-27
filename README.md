# FIX Client Application

A Spring Boot application that provides a REST API interface to send FIX orders to an exchange using QuickFIX/J with PostgreSQL persistence.

## Features

- FIX 4.4 protocol support
- REST API for sending orders and cancellations
- Execution report tracking
- Session status monitoring
- Support for MARKET and LIMIT orders
- **PostgreSQL database persistence** - Orders and executions survive restarts
- **Docker support** for PostgreSQL database

## Prerequisites

- Java 17+
- Maven 3.6+
- Docker and Docker Compose (for PostgreSQL)
- Running FIX Exchange Simulator (on port 9876)

## Quick Start

### 1. Start PostgreSQL Database

```bash
# Start PostgreSQL in Docker
docker-compose up -d

# Verify it's running
docker-compose ps
```

The PostgreSQL container will:
- Create a database named `fixclient`
- Create user `fixuser` with password `fixpassword`
- Expose port 5432
- Persist data in a Docker volume

### 2. Build the Application

```bash
mvn clean package
```

### 3. Run the Application

Make sure the exchange simulator is running first, then:

```bash
mvn spring-boot:run
```

The client will:
- Connect to PostgreSQL on localhost:5432
- Connect to the exchange on localhost:9876
- Start a REST API on port 8081

## Docker Commands

```bash
# Start PostgreSQL
docker-compose up -d

# Stop PostgreSQL (keeps data)
docker-compose stop

# Stop and remove containers (keeps data volume)
docker-compose down

# Stop and remove everything including data
docker-compose down -v

# View PostgreSQL logs
docker-compose logs -f postgres

# Connect to PostgreSQL CLI
docker exec -it fix-client-postgres psql -U fixuser -d fixclient
```

## Database Schema

The application uses JPA/Hibernate to automatically create and manage tables:

### Orders Table
| Column | Type | Description |
|--------|------|-------------|
| cl_ord_id | VARCHAR(36) | Primary key - Client Order ID |
| symbol | VARCHAR(20) | Trading symbol (e.g., AAPL) |
| side | VARCHAR(10) | BUY or SELL |
| order_type | VARCHAR(10) | MARKET or LIMIT |
| quantity | INT | Order quantity |
| price | DECIMAL(19,4) | Limit price |
| status | VARCHAR(20) | Order status |
| filled_quantity | INT | Filled quantity |
| leaves_quantity | INT | Remaining quantity |
| timestamp | TIMESTAMP | Creation time |
| updated_at | TIMESTAMP | Last update time |

### Executions Table
| Column | Type | Description |
|--------|------|-------------|
| exec_id | VARCHAR(50) | Primary key - Execution ID |
| order_id | VARCHAR(50) | Exchange order ID |
| cl_ord_id | VARCHAR(36) | Client Order ID |
| orig_cl_ord_id | VARCHAR(36) | Original order ID (for cancel/replace) |
| symbol | VARCHAR(20) | Trading symbol |
| side | VARCHAR(10) | BUY or SELL |
| exec_type | VARCHAR(20) | Execution type |
| order_status | VARCHAR(20) | Order status |
| last_price | DECIMAL(19,4) | Last execution price |
| last_quantity | INT | Last execution quantity |
| leaves_quantity | INT | Remaining quantity |
| cum_quantity | INT | Cumulative filled quantity |
| avg_price | DECIMAL(19,4) | Average fill price |
| timestamp | TIMESTAMP | Execution time |

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

### Amend Order
```bash
curl -X PUT "http://localhost:8081/api/orders/{clOrdId}" \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "AAPL",
    "side": "BUY",
    "newQuantity": 200,
    "newPrice": 155.00
  }'
```

### Get Orders
```bash
# All orders
curl http://localhost:8081/api/orders

# Open orders only
curl "http://localhost:8081/api/orders?openOnly=true"
curl http://localhost:8081/api/orders/open

# Specific order
curl http://localhost:8081/api/orders/{clOrdId}
```

### Get Executions
```bash
# All executions (limited to 100)
curl http://localhost:8081/api/executions

# Executions for specific order
curl "http://localhost:8081/api/executions?clOrdId=ABC123"

# Recent executions with custom limit
curl "http://localhost:8081/api/executions?limit=10"

# Execution count
curl http://localhost:8081/api/executions/count
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

### Application Configuration
Edit `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/fixclient
    username: fixuser
    password: fixpassword
```

### FIX Configuration
Edit `src/main/resources/quickfix-client.cfg`:
- Exchange host/port
- SenderCompID/TargetCompID
- Heartbeat interval

### Environment Variables
You can override database settings with environment variables:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://myhost:5432/mydb
export SPRING_DATASOURCE_USERNAME=myuser
export SPRING_DATASOURCE_PASSWORD=mypassword
mvn spring-boot:run
```

## Project Structure

```
fix-client/
├── docker-compose.yml          # PostgreSQL Docker configuration
├── init-db.sql                 # Database initialization script
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/example/client/
│   │   │   ├── FixClientApplication.java
│   │   │   ├── config/
│   │   │   │   └── FixConfig.java
│   │   │   ├── controller/
│   │   │   │   └── OrderController.java
│   │   │   ├── entity/                    # NEW: JPA Entities
│   │   │   │   ├── OrderEntity.java
│   │   │   │   └── ExecutionEntity.java
│   │   │   ├── fix/
│   │   │   │   └── ClientFixApplication.java
│   │   │   ├── model/
│   │   │   │   ├── AmendRequest.java
│   │   │   │   ├── CancelRequest.java
│   │   │   │   ├── ExecutionMessage.java
│   │   │   │   ├── OrderRequest.java
│   │   │   │   ├── OrderResponse.java
│   │   │   │   └── SessionStatus.java
│   │   │   ├── repository/                # NEW: JPA Repositories
│   │   │   │   ├── OrderRepository.java
│   │   │   │   └── ExecutionRepository.java
│   │   │   └── service/
│   │   │       ├── ExecutionService.java  # Updated for JPA
│   │   │       └── OrderService.java      # Updated for JPA
│   │   └── resources/
│   │       ├── application.yml            # Updated with DB config
│   │       └── quickfix-client.cfg
```

## Example Workflow

1. Start PostgreSQL:
   ```bash
   docker-compose up -d
   ```

2. Start the exchange simulator

3. Start this client:
   ```bash
   mvn spring-boot:run
   ```

4. Send a limit buy order:
   ```bash
   curl -X POST http://localhost:8081/api/orders \
     -H "Content-Type: application/json" \
     -d '{"symbol":"AAPL","side":"BUY","orderType":"LIMIT","quantity":100,"price":150.00}'
   ```

5. Check orders (persisted in database):
   ```bash
   curl http://localhost:8081/api/orders
   ```

6. Restart the application - orders will still be there!

7. Query the database directly:
   ```bash
   docker exec -it fix-client-postgres psql -U fixuser -d fixclient -c "SELECT * FROM orders;"
   ```

## Profiles

The application supports multiple profiles:

```bash
# Development (verbose logging)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Production (minimal logging, validates schema)
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

## Troubleshooting

### Database Connection Issues
```bash
# Check if PostgreSQL is running
docker-compose ps

# Check PostgreSQL logs
docker-compose logs postgres

# Test connection
docker exec -it fix-client-postgres psql -U fixuser -d fixclient -c "SELECT 1;"
```

### Reset Database
```bash
# Stop containers and remove volumes
docker-compose down -v

# Start fresh
docker-compose up -d
```

## License

MIT
